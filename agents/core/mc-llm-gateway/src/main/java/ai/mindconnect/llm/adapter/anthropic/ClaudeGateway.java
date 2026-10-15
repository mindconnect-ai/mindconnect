package ai.mindconnect.llm.adapter.anthropic;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.LlmCallEventBuilder;
import ai.mindconnect.llm.adapter.LlmHttpErrors;
import ai.mindconnect.llm.adapter.LlmWireLog;
import ai.mindconnect.llm.adapter.SseBlockReader;
import ai.mindconnect.llm.adapter.ToolCallBuilder;
import ai.mindconnect.llm.domain.*;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Anthropic Messages API adapter with streaming support.
 * <p>
 * Wire differences from the OpenAI format:
 * <ul>
 *   <li>The {@code system} prompt is a top-level field, not a message in the array.</li>
 *   <li>Tool results are wrapped as {@code tool_result} content blocks inside a
 *       {@code user} message — there is no {@code tool} role.</li>
 *   <li>Streaming events are {@code content_block_delta} (text or
 *       {@code input_json_delta} for tool arguments) and {@code message_delta}
 *       for the finish reason and token usage.</li>
 * </ul>
 */
public class ClaudeGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(ClaudeGateway.class);

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionHelper encryption;
    private final EnvVarResolver env;
    private final ObjectWriter prettyWriter;

    /** Placeholders resolve from the process environment alone — the library and desktop case. */
    public ClaudeGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        this(httpClient, objectMapper, encryption, EnvVarResolver.system());
    }

    /** @param env where {@code ${VAR}} placeholders in a config resolve from — on a server, the user's, the namespace's and the process's variables in that order */
    public ClaudeGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption,
                         EnvVarResolver env) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.encryption = encryption;
        this.env = env;
        this.prettyWriter = objectMapper.writerWithDefaultPrettyPrinter();
    }

    @Override
    public void chatStreaming(LlmConfig config, LlmRequest request,
                              Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        config = config.resolved(env, encryption);
        int msgCount = request.messages() == null ? 0 : request.messages().size();
        int toolCount = request.tools() == null ? 0 : request.tools().size();
        log.debug("Claude stream → model={} messages={} tools={}", config.model(), msgCount, toolCount);
        LlmCallEventBuilder trace = new LlmCallEventBuilder(config, request);

        StringBuilder assistantText = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCallBuilders = new TreeMap<>();
        Integer errorStatus = null;
        String errorBody = null;

        String body;
        try {
            ObjectNode requestNode = buildRequestNode(config, request);
            trace.request(prettyWriter, requestNode);
            LlmWireLog.request(prettyWriter, requestNode);
            body = objectMapper.writeValueAsString(requestNode);
        } catch (IOException e) {
            trace.report(listener, null, 0, 0, null, null, e.getMessage());
            throw new RuntimeException("Failed to build Anthropic request", e);
        }

        String baseUrl = LlmProvider.ANTHROPIC.baseUrlOr(config.baseUrl());

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();

        // Anthropic streams multiple content blocks; we track the current block type by index.
        List<String> blockTypes = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        FinishReason finish = FinishReason.STOP;
        StringBuilder debugAccumulatedText = LlmWireLog.enabled() ? new StringBuilder() : null;
        int textTokenCount = 0;
        boolean cancelledClean = false;

        Call call = httpClient.newCall(httpRequest);
        cancellation.registerAbort(call::cancel);

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                errorStatus = response.code();
                errorBody = response.body() != null ? response.body().string() : "(no body)";
                trace.report(listener, null, 0, 0, null, errorStatus, errorBody);
                throw LlmHttpErrors.failure(log, "Anthropic", config, errorStatus,
                        response.header("retry-after"), errorBody);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {

                SseBlockReader blocks = new SseBlockReader(reader, cancellation);
                String block;
                while ((block = blocks.next()) != null) {
                    trace.responseEvents().add(block);
                    String dataLine = SseBlockReader.dataLine(block);
                    if (dataLine == null) continue;
                    ClaudeParsed p = parseClaudeEvent(SseBlockReader.eventType(block), dataLine,
                            toolCallBuilders, handler, blockTypes);
                    if (p.text != null) {
                        assistantText.append(p.text);
                        if (debugAccumulatedText != null) debugAccumulatedText.append(p.text);
                        textTokenCount += p.textDeltas;
                    }
                    if (p.finishReason != null) finish = p.finishReason;
                    if (p.inputTokens > 0) inputTokens = p.inputTokens;
                    if (p.outputTokens > 0) outputTokens = p.outputTokens;
                }
                cancelledClean = blocks.cancelled();
                if (blocks.trailing() != null) trace.responseEvents().add(blocks.trailing());
            }
        } catch (IOException e) {
            if (cancellation.isCancelled()) {
                log.debug("Claude stream cancelled by caller after {}ms", trace.elapsedMillis());
                cancelledClean = true;
            } else {
                trace.report(listener, summary(assistantText, toolCallBuilders),
                        inputTokens, outputTokens,
                        finish != null ? finish.name() : null, errorStatus,
                        errorBody != null ? errorBody : e.getMessage());
                throw new RuntimeException("Failed to stream from Anthropic", e);
            }
        } catch (RuntimeException e) {
            trace.report(listener, summary(assistantText, toolCallBuilders),
                    inputTokens, outputTokens,
                    finish != null ? finish.name() : null, errorStatus, errorBody);
            throw e;
        }

        trace.report(listener, summary(assistantText, toolCallBuilders),
                inputTokens, outputTokens,
                finish != null ? finish.name() : null, null, null);

        if (cancelledClean) {
            log.debug("Claude stream cancelled by caller after {}ms", trace.elapsedMillis());
            return;
        }

        if (debugAccumulatedText != null && debugAccumulatedText.length() > 0) {
            LlmWireLog.response(textTokenCount, debugAccumulatedText);
        }
        log.debug("Claude stream ← finish={} text-tokens={} in={}t out={}t {}ms",
                finish, textTokenCount, inputTokens, outputTokens, trace.elapsedMillis());

        handler.accept(new LlmStreamChunk.Done(finish, inputTokens, outputTokens));
    }

    /** Per-event parse result. */
    private record ClaudeParsed(
            String text, int textDeltas, FinishReason finishReason,
            int inputTokens, int outputTokens) {}

    private ClaudeParsed parseClaudeEvent(String eventType, String data,
                                           Map<Integer, ToolCallBuilder> builders,
                                           Consumer<LlmStreamChunk> handler,
                                           List<String> blockTypes) {
        try {
            JsonNode root = objectMapper.readTree(data);
            switch (eventType) {
                case "content_block_start" -> {
                    int index = root.path("index").asInt(0);
                    String blockType = root.path("content_block").path("type").asText("");
                    while (blockTypes.size() <= index) blockTypes.add("");
                    blockTypes.set(index, blockType);
                    if ("tool_use".equals(blockType)) {
                        String id = root.path("content_block").path("id").asText(null);
                        String name = root.path("content_block").path("name").asText(null);
                        handler.accept(new LlmStreamChunk.ToolCallDelta(index, id, name, null));
                        builders.computeIfAbsent(index, i -> new ToolCallBuilder()).feed(id, name, null);
                    } else if ("thinking".equals(blockType) || "redacted_thinking".equals(blockType)) {
                        // redacted_thinking carries its encrypted payload on the
                        // start event; plain thinking accumulates via deltas.
                        String redactedData = root.path("content_block").path("data").asText(null);
                        handler.accept(new LlmStreamChunk.ThinkingDelta(index, blockType, null, null, redactedData));
                    }
                }
                case "content_block_delta" -> {
                    int index = root.path("index").asInt(0);
                    JsonNode delta = root.path("delta");
                    String deltaType = delta.path("type").asText("");
                    if ("text_delta".equals(deltaType)) {
                        String text = delta.path("text").asText("");
                        if (!text.isEmpty()) {
                            handler.accept(new LlmStreamChunk.TextDelta(text));
                            return new ClaudeParsed(text, 1, null, 0, 0);
                        }
                    } else if ("input_json_delta".equals(deltaType)) {
                        String argsFrag = delta.path("partial_json").asText(null);
                        handler.accept(new LlmStreamChunk.ToolCallDelta(index, null, null, argsFrag));
                        builders.computeIfAbsent(index, i -> new ToolCallBuilder()).feed(null, null, argsFrag);
                    } else if ("thinking_delta".equals(deltaType)) {
                        String thoughtFrag = delta.path("thinking").asText(null);
                        handler.accept(new LlmStreamChunk.ThinkingDelta(index, null, thoughtFrag, null, null));
                    } else if ("signature_delta".equals(deltaType)) {
                        String signature = delta.path("signature").asText(null);
                        handler.accept(new LlmStreamChunk.ThinkingDelta(index, null, null, signature, null));
                    }
                }
                case "message_delta" -> {
                    JsonNode delta = root.path("delta");
                    String stopReason = delta.path("stop_reason").asText(null);
                    FinishReason finish = stopReason != null ? parseFinishReason(stopReason) : null;
                    JsonNode usage = root.path("usage");
                    int outputTokens = usage.isObject() ? usage.path("output_tokens").asInt(0) : 0;
                    return new ClaudeParsed(null, 0, finish, 0, outputTokens);
                }
                case "message_start" -> {
                    JsonNode usage = root.path("message").path("usage");
                    int inputTokens = usage.isObject() ? usage.path("input_tokens").asInt(0) : 0;
                    return new ClaudeParsed(null, 0, null, inputTokens, 0);
                }
                default -> { /* ping, error, etc. — ignore */ }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Claude event: {} — payload: {}", e.getMessage(), data);
        }
        return new ClaudeParsed(null, 0, null, 0, 0);
    }

    private LlmCallEvent.ResponseSummary summary(StringBuilder assistantText,
                                                  Map<Integer, ToolCallBuilder> builders) {
        return LlmCallEventBuilder.summary(assistantText, ToolCallBuilder.buildAll(builders.values(), objectMapper));
    }

    private static FinishReason parseFinishReason(String raw) {
        return switch (raw) {
            case "end_turn" -> FinishReason.STOP;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.LENGTH;
            default -> FinishReason.ERROR;
        };
    }

    /**
     * Builds the Anthropic Messages API request body.
     * <p>
     * Key differences from the OpenAI wire format:
     * <ul>
     *   <li>{@code system} is a top-level string extracted from the first SYSTEM message.</li>
     *   <li>TOOL role messages are converted to {@code user} messages with a
     *       {@code tool_result} content block.</li>
     *   <li>ASSISTANT messages that contain tool calls use a {@code tool_use} content block.</li>
     * </ul>
     */
    // package-private for testing the wire JSON without a live HTTP call
    ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        root.put("max_tokens", request.maxOutputTokens() < 0 ? config.maxOutputTokens() : request.maxOutputTokens());

        // Reasoning controls come from the generic per-config/per-request param map.
        // thinking: "adaptive" | "disabled" (default: omit → no thinking)
        // thinkingDisplay: "summarized" | "omitted" (default omitted by the API)
        // effort: low | medium | high | xhigh | max
        Map<String, Object> params = LlmParams.merge(config, request);
        String thinking = LlmParams.string(params, "thinking");
        boolean thinkingEnabled = "adaptive".equalsIgnoreCase(thinking);
        if (thinkingEnabled) {
            ObjectNode thinkingNode = root.putObject("thinking");
            thinkingNode.put("type", "adaptive");
            String display = LlmParams.string(params, "thinkingDisplay");
            if (display != null) thinkingNode.put("display", display);
        } else if ("disabled".equalsIgnoreCase(thinking)) {
            root.putObject("thinking").put("type", "disabled");
        }

        String effort = LlmParams.string(params, "effort");
        if (effort != null) {
            root.putObject("output_config").put("effort", effort);
        }

        // Opus 4.7/4.8 reject sampling params when adaptive thinking is on (HTTP 400).
        // Only send temperature when thinking is not adaptive.
        if (!thinkingEnabled) {
            double temperature = request.temperature() < 0 ? config.defaultTemperature() : request.temperature();
            root.put("temperature", temperature);
        }

        // Extract system message — Anthropic expects it as a top-level field.
        List<LlmMessage> nonSystemMessages = new ArrayList<>();
        for (LlmMessage msg : request.messages()) {
            if (msg.role() == MessageRole.SYSTEM) {
                root.put("system", msg.content() != null ? msg.content() : "");
            } else {
                nonSystemMessages.add(msg);
            }
        }

        ArrayNode messages = root.putArray("messages");
        for (LlmMessage msg : nonSystemMessages) {
            if (msg.role() == MessageRole.TOOL) {
                // Tool results become a user message with a tool_result content block.
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", "user");
                ArrayNode content = msgNode.putArray("content");
                ObjectNode block = content.addObject();
                block.put("type", "tool_result");
                block.put("tool_use_id", msg.toolCallId());
                block.put("content", msg.content() != null ? msg.content() : "");
            } else if (msg.role() == MessageRole.ASSISTANT
                    && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                // Assistant message with tool calls — emit as content blocks.
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", "assistant");
                ArrayNode content = msgNode.putArray("content");
                // Thinking blocks MUST come first and unchanged (signature included),
                // or Anthropic rejects the replayed turn with HTTP 400. A block
                // without a signature came from another provider's model (the
                // conversation switched configs) — Anthropic would reject it
                // just the same, so it stays out.
                if (msg.thinkingBlocks() != null) {
                    for (ThinkingBlock tb : msg.thinkingBlocks()) {
                        if ("redacted_thinking".equals(tb.type())) {
                            ObjectNode block = content.addObject();
                            block.put("type", "redacted_thinking");
                            block.put("data", tb.data());
                        } else if (tb.signature() != null) {
                            ObjectNode block = content.addObject();
                            block.put("type", "thinking");
                            block.put("thinking", tb.text() != null ? tb.text() : "");
                            block.put("signature", tb.signature());
                        }
                    }
                }
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode block = content.addObject();
                    block.put("type", "tool_use");
                    block.put("id", tc.id());
                    block.put("name", tc.name());
                    block.set("input", objectMapper.valueToTree(tc.arguments()));
                }
            } else if (msg.hasMedia()) {
                // A user message with images / documents — content blocks,
                // media inline as base64.
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", msg.role().name().toLowerCase());
                ArrayNode content = msgNode.putArray("content");
                for (LlmContent part : msg.parts()) {
                    ObjectNode block = content.addObject();
                    switch (part) {
                        case LlmContent.Text t -> block.put("type", "text").put("text", t.text());
                        case LlmContent.Image i -> {
                            block.put("type", "image");
                            base64Source(block, i.mediaType(), i.base64());
                        }
                        case LlmContent.Document d -> {
                            block.put("type", "document");
                            base64Source(block, d.mediaType(), d.base64());
                            if (d.name() != null) block.put("title", d.name());
                        }
                    }
                }
            } else {
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", msg.role().name().toLowerCase());
                msgNode.put("content", msg.content() != null ? msg.content() : "");
            }
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode toolNode = toolsNode.addObject();
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                toolNode.set("input_schema", objectMapper.valueToTree(tool.parametersSchema()));
            }
        }

        return root;
    }

    /** The {@code source} of an image or document block: inline base64. */
    private static void base64Source(ObjectNode block, String mediaType, String base64) {
        ObjectNode source = block.putObject("source");
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", base64);
    }
}
