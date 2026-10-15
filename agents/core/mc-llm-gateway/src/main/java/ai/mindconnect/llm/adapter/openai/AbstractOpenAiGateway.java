package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.LlmWireLog;
import ai.mindconnect.llm.adapter.SseBlockReader;
import ai.mindconnect.llm.adapter.SseStreamingGateway;
import ai.mindconnect.llm.adapter.StreamDelta;
import ai.mindconnect.llm.adapter.ToolCallBuilder;
import ai.mindconnect.llm.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Shared streaming logic for all OpenAI-compatible endpoints.
 * Subclasses override {@link #endpointUrl} and {@link #authHeader} to handle
 * provider-specific URL shapes and authentication schemes.
 */
abstract class AbstractOpenAiGateway extends SseStreamingGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAiGateway.class);
    private static final MediaType JSON = MediaType.get("application/json");

    AbstractOpenAiGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption,
                          EnvVarResolver env) {
        super("OpenAI-compatible", httpClient, objectMapper, encryption, env);
    }

    @Override
    protected Stream newStream() {
        return new OpenAiStream();
    }

    /** One Chat Completions stream: its tool-call builders and the think-tag splitter. */
    private final class OpenAiStream extends Stream {
        private final Map<Integer, ToolCallBuilder> builders = new TreeMap<>();
        private final ThinkTagSplitter thinkTags = new ThinkTagSplitter();

        @Override
        protected StreamDelta parse(String block, Consumer<LlmStreamChunk> handler) {
            String dataLine = SseBlockReader.dataLine(block);
            if (dataLine == null) return StreamDelta.NONE;
            if ("[DONE]".equals(dataLine)) return StreamDelta.END;
            return parseOpenAiDelta(dataLine, builders, thinkTags, handler);
        }

        /** A tail held back for a {@code <think>} tag that never came is answer text. */
        @Override
        protected StreamDelta flush(Consumer<LlmStreamChunk> handler) {
            ThinkTagSplitter.Split tail = thinkTags.flush();
            if (tail.thinking() != null) {
                handler.accept(new LlmStreamChunk.ThinkingDelta(0, "thinking", tail.thinking(), null, null));
            }
            if (tail.text() == null) return StreamDelta.NONE;
            handler.accept(new LlmStreamChunk.TextDelta(tail.text()));
            return new StreamDelta(tail.text(), 1, null, 0, 0, false);
        }

        @Override
        protected List<ToolCall> toolCalls() {
            return ToolCallBuilder.buildAll(builders.values(), objectMapper);
        }
    }

    /** Full URL for the chat/completions endpoint, derived from the config. */
    protected abstract String endpointUrl(LlmConfig config);

    /** Value of the HTTP Authorization (or equivalent) header. */
    protected abstract String authHeader(LlmConfig config);

    /** Name of the HTTP header carrying the auth value (default: Authorization). */
    protected String authHeaderName(LlmConfig config) {
        return "Authorization";
    }

    @Override
    protected Request httpRequest(LlmConfig config, String body) {
        return new Request.Builder()
                .url(endpointUrl(config))
                .header(authHeaderName(config), authHeader(config))
                .post(RequestBody.create(body, JSON))
                .build();
    }

    /**
     * Executes the call — and when the server answers 400 naming
     * {@code reasoning_effort}, once more without the field. The model rules in
     * {@link #buildRequestNode} keep the field away where OpenAI is known to
     * refuse it, but which models and combinations those are moves with their
     * releases (gpt-5.4-mini refuses it together with function tools on Chat
     * Completions, below the gpt-5.6 line the rules draw), so where the rules
     * guess wrong the gateway asks instead of failing the turn. The retry
     * replaces {@code call[0]} so the cancellation hook reaches the attempt in
     * flight.
     */
    @Override
    protected Response open(Call[] call, LlmConfig config, ObjectNode requestNode) throws IOException {
        Response response = call[0].execute();
        if (response.code() != 400 || !requestNode.has("reasoning_effort")) return response;
        String errorBody = response.peekBody(64 * 1024).string();
        if (!errorBody.contains("reasoning_effort")) return response;
        response.close();
        log.warn("{} refused reasoning_effort for model {} — retrying without it: {}",
                config.provider(), config.model(), errorBody.replaceAll("\\s+", " ").trim());
        requestNode.remove("reasoning_effort");
        LlmWireLog.request(prettyWriter, requestNode);
        call[0] = httpClient.newCall(httpRequest(config, objectMapper.writeValueAsString(requestNode)));
        return call[0].execute();
    }

    /**
     * Parses one OpenAI {@code data: {...}} payload, forwards the resulting
     * chunks to the live stream handler, and feeds the tool-call builders.
     *
     * <p>Reasoning reaches the handler as {@link LlmStreamChunk.ThinkingDelta}
     * from whichever channel the server uses: the {@code reasoning_content}
     * field (LM Studio, vLLM, DeepSeek, xAI, Moonshot), the {@code reasoning}
     * field (Ollama, Groq, OpenRouter), or {@code <think>} tags inline in the
     * content, which {@code thinkTags} cuts out. Package-private for tests.
     */
    StreamDelta parseOpenAiDelta(String data,
                                 Map<Integer, ToolCallBuilder> builders,
                                 ThinkTagSplitter thinkTags,
                                 Consumer<LlmStreamChunk> handler) {
        try {
            JsonNode root = objectMapper.readTree(data);
            // Some backends (LM Studio among them) report failures as an SSE
            // DATA event ({"error":{...}}) instead of an HTTP error — mid-way
            // through an otherwise 200 stream. Swallowing it would let an
            // empty answer complete "successfully"; a broken call must FAIL,
            // readably, so the task records it and the UI says so.
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                String message = error.hasNonNull("message")
                        ? error.get("message").asText() : error.toString();
                throw new StreamErrored("LLM stream reported an error: " + message);
            }
            JsonNode choice = root.path("choices").path(0);
            JsonNode delta = choice.path("delta");

            String reasoning = firstText(delta, "reasoning_content", "reasoning");
            if (reasoning != null) {
                handler.accept(new LlmStreamChunk.ThinkingDelta(0, "thinking", reasoning, null, null));
            }

            String text = delta.path("content").asText("");
            // Some models occasionally leak Harmony-style channel markers
            // or "to=functions.x" envelopes into the content channel
            // instead of using the proper tool_calls field. Scrub those
            // before they reach the chat — see ContentLeakSanitizer.
            if (!text.isEmpty()) {
                text = ContentLeakSanitizer.sanitize(text);
            }
            ThinkTagSplitter.Split split = thinkTags.feed(text);
            if (split.thinking() != null) {
                handler.accept(new LlmStreamChunk.ThinkingDelta(0, "thinking", split.thinking(), null, null));
            }
            text = split.text() == null ? "" : split.text();
            int textDeltas = 0;
            if (text != null && !text.isEmpty()) {
                handler.accept(new LlmStreamChunk.TextDelta(text));
                textDeltas = 1;
            } else {
                text = null;
            }

            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").asInt(0);
                    String id = tc.has("id") ? tc.path("id").asText() : null;
                    JsonNode fn = tc.path("function");
                    String name = fn.has("name") ? fn.path("name").asText() : null;
                    String argsFrag = fn.has("arguments") ? fn.path("arguments").asText() : null;
                    handler.accept(new LlmStreamChunk.ToolCallDelta(index, id, name, argsFrag));
                    builders.computeIfAbsent(index, i -> new ToolCallBuilder())
                            .feed(id, name, argsFrag);
                }
            }

            FinishReason finish = null;
            String finishRaw = choice.path("finish_reason").asText(null);
            if (finishRaw != null && !finishRaw.isEmpty() && !"null".equals(finishRaw)) {
                finish = parseFinishReason(finishRaw);
            }

            int promptTokens = 0;
            int completionTokens = 0;
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                promptTokens = usage.path("prompt_tokens").asInt(0);
                completionTokens = usage.path("completion_tokens").asInt(0);
            }
            return new StreamDelta(text, textDeltas, finish, promptTokens, completionTokens, false);
        } catch (StreamErrored e) {
            throw e;                       // a reported backend error must FAIL the call
        } catch (Exception e) {
            log.warn("Failed to parse OpenAI delta: {} — payload: {}", e.getMessage(), data);
            return StreamDelta.NONE;
        }
    }

    /** The first of {@code fields} that holds non-empty text on {@code node}, else {@code null}. */
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isEmpty()) return value.asText();
        }
        return null;
    }

    /** A backend that reports its failure as an SSE data event — must not be swallowed as a parse hiccup. */
    private static final class StreamErrored extends RuntimeException {
        private StreamErrored(String message) {
            super(message);
        }
    }

    private static FinishReason parseFinishReason(String raw) {
        return switch (raw) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            default -> FinishReason.ERROR;
        };
    }

    @Override
    protected ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        boolean reasoning = OpenAiModelPolicy.isReasoningModel(config.model());
        if (!reasoning) {
            root.put("temperature", request.temperature() < 0 ? config.defaultTemperature() : request.temperature());
        }
        int maxTokens = request.maxOutputTokens() < 0 ? config.maxOutputTokens() : request.maxOutputTokens();
        // o-series models require max_completion_tokens; all others use max_tokens
        root.put(reasoning ? "max_completion_tokens" : "max_tokens", maxTokens);

        // How hard a reasoning model thinks — the config's default, overridden
        // per request. Which levels a server takes is its business. A field a
        // server does not know it ignores (LM Studio, Ollama) — except OpenAI,
        // which rejects it on a model that does not reason (HTTP 400), so there
        // it goes only with its reasoning models. Azure names deployments, not
        // models, so nothing can be told from the name and the field is sent.
        Map<String, Object> params = LlmParams.merge(config, request);
        String reasoningEffort = LlmParams.string(params, "reasoning_effort");
        boolean effortAccepted = !OPENAI_ITSELF.contains(config.provider()) || reasoning;
        // From gpt-5.6 on, OpenAI refuses function tools together with reasoning
        // on Chat Completions (HTTP 400) — and those models reason by default,
        // so leaving the field out is refused too. With tools the effort has to
        // be "none" here; a config that wants both goes through the Responses
        // API (provider OPENAI), and only OPENAI_CHAT_COMPLETIONS lands here.
        boolean tools = request.tools() != null && !request.tools().isEmpty();
        if (OPENAI_ITSELF.contains(config.provider()) && tools
                && OpenAiModelPolicy.refusesToolsWithReasoningOnChat(config.model())) {
            if (reasoningEffort != null && !"none".equals(reasoningEffort)) {
                log.debug("{} takes no reasoning with tools on Chat Completions — sending reasoning_effort=none instead of {}",
                        config.model(), reasoningEffort);
            }
            reasoningEffort = "none";
        }
        if (reasoningEffort != null && effortAccepted) {
            root.put("reasoning_effort", reasoningEffort);
        }

        ArrayNode messages = root.putArray("messages");
        for (LlmMessage msg : request.messages()) {
            ObjectNode msgNode = messages.addObject();
            msgNode.put("role", msg.role().name().toLowerCase());

            if (msg.role() == MessageRole.TOOL) {
                msgNode.put("tool_call_id", msg.toolCallId());
                msgNode.put("content", msg.content() != null ? msg.content() : "");
            } else if (msg.role() == MessageRole.ASSISTANT && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                msgNode.putNull("content");
                ArrayNode tcArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = tcArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode fn = tcNode.putObject("function");
                    fn.put("name", tc.name());
                    fn.put("arguments", objectMapper.writeValueAsString(tc.arguments()));
                }
            } else if (msg.hasMedia()) {
                renderContentBlocks(msgNode.putArray("content"), msg, config);
            } else {
                msgNode.put("content", msg.content() != null ? msg.content() : "");
            }
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put("type", "function");
                ObjectNode fn = toolNode.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", objectMapper.valueToTree(tool.parametersSchema()));
            }
        }

        // Ask the endpoint for usage stats in the final chunk.
        ObjectNode streamOptions = root.putObject("stream_options");
        streamOptions.put("include_usage", true);

        return root;
    }

    /** OpenAI's own API, whichever way a config reaches it. */
    private static final Set<LlmProvider> OPENAI_ITSELF =
            EnumSet.of(LlmProvider.OPENAI, LlmProvider.OPENAI_CHAT_COMPLETIONS);

    /**
     * The endpoints that take a {@code file} content block. It is OpenAI's
     * own extension of Chat Completions — OpenRouter mirrors it, Azure serves
     * OpenAI's models; every other compatible server (LM Studio, Ollama,
     * Groq, Mistral, …) rejects the request with a 400 for an unknown type.
     */
    private static final Set<LlmProvider> FILE_BLOCK_PROVIDERS =
            EnumSet.of(LlmProvider.OPENAI, LlmProvider.OPENAI_CHAT_COMPLETIONS,
                    LlmProvider.AZURE_OPENAI, LlmProvider.OPENROUTER);

    /**
     * A user message with media as the Chat Completions content array: text
     * blocks as {@code text}, images as a data-URL {@code image_url}, documents
     * as a {@code file} block with inline {@code file_data} — where the
     * endpoint takes one; elsewhere a document becomes a text block that
     * says what it is, so a config that declares {@code DOCUMENTS} on such a
     * server still gets an answer instead of a 400. Text-only messages never
     * come here — they stay the plain string every endpoint understands.
     */
    private static void renderContentBlocks(ArrayNode content, LlmMessage msg, LlmConfig config) {
        boolean fileBlocks = config.provider() != null && FILE_BLOCK_PROVIDERS.contains(config.provider());
        for (LlmContent part : msg.parts()) {
            ObjectNode block = content.addObject();
            switch (part) {
                case LlmContent.Text t -> block.put("type", "text").put("text", t.text());
                case LlmContent.Image i -> block.put("type", "image_url")
                        .putObject("image_url").put("url", dataUrl(i.mediaType(), i.base64()));
                case LlmContent.Document d when fileBlocks -> block.put("type", "file")
                        .putObject("file")
                        .put("filename", d.name())
                        .put("file_data", dataUrl(d.mediaType(), d.base64()));
                case LlmContent.Document d -> block.put("type", "text").put("text",
                        "[System note — document attached: " + d.name() + " (" + d.mediaType()
                                + ") — this endpoint takes no file blocks, so its content is not here.]");
            }
        }
    }

    static String dataUrl(String mediaType, String base64) {
        return "data:" + mediaType + ";base64," + base64;
    }
}
