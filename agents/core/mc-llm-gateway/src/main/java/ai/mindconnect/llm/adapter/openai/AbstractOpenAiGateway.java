package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.*;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Shared streaming logic for all OpenAI-compatible endpoints.
 * Subclasses override {@link #endpointUrl} and {@link #authHeader} to handle
 * provider-specific URL shapes and authentication schemes.
 */
abstract class AbstractOpenAiGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractOpenAiGateway.class);
    static final Logger wire = LoggerFactory.getLogger("ai.mindconnect.llm.wire");
    private static final MediaType JSON = MediaType.get("application/json");

    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected final EncryptionHelper encryption;
    private final ObjectWriter prettyWriter;

    AbstractOpenAiGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.encryption = encryption;
        this.prettyWriter = objectMapper.writerWithDefaultPrettyPrinter();
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
    public void chatStreaming(LlmConfig config, LlmRequest request,
                              Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        config = config.resolved(encryption);
        int msgCount = request.messages() == null ? 0 : request.messages().size();
        int toolCount = request.tools() == null ? 0 : request.tools().size();
        log.debug("LLM stream → model={} messages={} tools={}", config.model(), msgCount, toolCount);
        long start = System.currentTimeMillis();
        Instant startedAt = Instant.ofEpochMilli(start);

        // Trace buffers — populated as the stream progresses, drained into
        // LlmCallEvent in the finally path (success or error).
        String prettyRequestJson = null;
        List<String> responseEvents = new ArrayList<>();
        StringBuilder assistantText = new StringBuilder();
        Map<Integer, OpenAiToolCallBuilder> toolCallBuilders = new TreeMap<>();
        Integer errorStatus = null;
        String errorBody = null;

        ObjectNode requestNode;
        String body;
        try {
            requestNode = buildRequestNode(config, request);
            try { prettyRequestJson = prettyWriter.writeValueAsString(requestNode); } catch (Exception ignored) {}
            logWireRequest(requestNode);
            body = objectMapper.writeValueAsString(requestNode);
        } catch (IOException e) {
            fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                    responseEvents, null, 0, 0, null, null, e.getMessage());
            throw new RuntimeException("Failed to build OpenAI-compatible request", e);
        }

        Request httpRequest = new Request.Builder()
                .url(endpointUrl(config))
                .header(authHeaderName(config), authHeader(config))
                .post(RequestBody.create(body, JSON))
                .build();

        Call call = httpClient.newCall(httpRequest);
        // Wire the cancellation handle to the OkHttp call so cancel() actually
        // closes the upstream HTTP connection — not just flips a flag.
        cancellation.registerAbort(call::cancel);

        int textTokenCount = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        FinishReason finish = FinishReason.STOP;
        StringBuilder debugAccumulatedText = wire.isDebugEnabled() ? new StringBuilder() : null;
        boolean cancelledClean = false;

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                errorStatus = response.code();
                errorBody = response.body() != null ? response.body().string() : "(no body)";
                log.warn("LLM stream error: HTTP {} — body: {}", errorStatus, errorBody);
                // Transient errors (rate limit / overloaded) are typed so the
                // generic RetryingLlmGateway can back off and retry.
                if (LlmTransientException.isTransient(errorStatus)) {
                    throw new LlmTransientException(errorStatus,
                            LlmTransientException.parseRetryAfterMillis(response.header("retry-after")),
                            "OpenAI-compatible stream error: " + errorStatus);
                }
                throw new RuntimeException("OpenAI-compatible stream error: " + errorStatus);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {

                // SSE block accumulator: collect lines until a blank line,
                // then process and store the block as one trace entry.
                StringBuilder block = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancellation.isCancelled()) { cancelledClean = true; break; }
                    if (line.isEmpty()) {
                        // Block boundary — capture and parse.
                        if (block.length() > 0) {
                            String blockText = block.toString();
                            responseEvents.add(blockText);
                            // Strip trailing newline before parsing.
                            String dataLine = extractDataLine(blockText);
                            if (dataLine != null) {
                                if ("[DONE]".equals(dataLine)) {
                                    block.setLength(0);
                                    break;
                                }
                                ParsedDelta d = parseOpenAiDelta(dataLine, toolCallBuilders, handler);
                                textTokenCount += d.textDeltas;
                                if (d.text != null) {
                                    assistantText.append(d.text);
                                    if (debugAccumulatedText != null) debugAccumulatedText.append(d.text);
                                }
                                if (d.finishReason != null) finish = d.finishReason;
                                if (d.promptTokens > 0) inputTokens = d.promptTokens;
                                if (d.completionTokens > 0) outputTokens = d.completionTokens;
                            }
                            block.setLength(0);
                        }
                        continue;
                    }
                    if (block.length() > 0) block.append('\n');
                    block.append(line);
                }
                // Some providers omit the final blank line; flush whatever is left.
                if (block.length() > 0 && !cancelledClean) {
                    responseEvents.add(block.toString());
                }
            }
        } catch (IOException e) {
            if (call.isCanceled()) {
                log.debug("LLM stream cancelled by caller after {}ms", System.currentTimeMillis() - start);
                cancelledClean = true;
            } else {
                fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                        responseEvents, summary(assistantText, toolCallBuilders),
                        inputTokens, outputTokens,
                        finish != null ? finish.name() : null, errorStatus,
                        errorBody != null ? errorBody : e.getMessage());
                throw new RuntimeException("Failed to stream from OpenAI-compatible endpoint", e);
            }
        } catch (RuntimeException e) {
            fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                    responseEvents, summary(assistantText, toolCallBuilders),
                    inputTokens, outputTokens,
                    finish != null ? finish.name() : null, errorStatus, errorBody);
            throw e;
        }

        // Successful (or cleanly cancelled) completion path.
        fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                responseEvents, summary(assistantText, toolCallBuilders),
                inputTokens, outputTokens,
                finish != null ? finish.name() : null, null, null);

        if (cancelledClean) {
            log.debug("LLM stream cancelled by caller after {}ms", System.currentTimeMillis() - start);
            return;
        }

        if (debugAccumulatedText != null && debugAccumulatedText.length() > 0) {
            wire.debug("← stream response (accumulated, {} text tokens):\n{}",
                    textTokenCount, debugAccumulatedText);
        }
        log.debug("LLM stream ← finish={} text-tokens={} in={}t out={}t {}ms",
                finish, textTokenCount, inputTokens, outputTokens, System.currentTimeMillis() - start);

        handler.accept(new LlmStreamChunk.Done(finish, inputTokens, outputTokens));
    }

    /** Pulls the {@code data: …} payload out of a single SSE block. */
    private static String extractDataLine(String block) {
        for (String line : block.split("\n")) {
            if (line.startsWith("data: ")) return line.substring(6).trim();
        }
        return null;
    }

    /** Per-delta extraction result so the streaming loop stays linear. */
    private record ParsedDelta(
            String text, int textDeltas, FinishReason finishReason,
            int promptTokens, int completionTokens) {}

    /**
     * Parses one OpenAI {@code data: {...}} payload, forwards the resulting
     * chunks to the live stream handler, and feeds the tool-call builders.
     */
    private ParsedDelta parseOpenAiDelta(String data,
                                          Map<Integer, OpenAiToolCallBuilder> builders,
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

            String text = delta.path("content").asText("");
            // Some models occasionally leak Harmony-style channel markers
            // or "to=functions.x" envelopes into the content channel
            // instead of using the proper tool_calls field. Scrub those
            // before they reach the chat — see ContentLeakSanitizer.
            if (!text.isEmpty()) {
                text = ContentLeakSanitizer.sanitize(text);
            }
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
                    builders.computeIfAbsent(index, i -> new OpenAiToolCallBuilder())
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
            return new ParsedDelta(text, textDeltas, finish, promptTokens, completionTokens);
        } catch (StreamErrored e) {
            throw e;                       // a reported backend error must FAIL the call
        } catch (Exception e) {
            log.warn("Failed to parse OpenAI delta: {} — payload: {}", e.getMessage(), data);
            return new ParsedDelta(null, 0, null, 0, 0);
        }
    }

    /** A backend that reports its failure as an SSE data event — must not be swallowed as a parse hiccup. */
    private static final class StreamErrored extends RuntimeException {
        private StreamErrored(String message) {
            super(message);
        }
    }

    /** Builds the {@link LlmCallEvent.ResponseSummary} from accumulators. */
    private LlmCallEvent.ResponseSummary summary(StringBuilder assistantText,
                                                  Map<Integer, OpenAiToolCallBuilder> builders) {
        String text = assistantText.length() == 0 ? null : assistantText.toString();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (OpenAiToolCallBuilder b : builders.values()) {
            ToolCall built = b.build(objectMapper);
            if (built != null) toolCalls.add(built);
        }
        if (text == null && toolCalls.isEmpty()) return null;
        return new LlmCallEvent.ResponseSummary(text, toolCalls);
    }

    /**
     * Reassembles a streaming tool call. Mirrors the builder in ToolLoopRunner —
     * lives here too because the adapter needs to surface the assembled list
     * via the listener (for trace persistence), independent of the runtime.
     */
    private static final class OpenAiToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder argsJson = new StringBuilder();

        void feed(String id, String name, String argsFrag) {
            if (id != null) this.id = id;
            if (name != null) this.name = name;
            if (argsFrag != null) this.argsJson.append(argsFrag);
        }

        ToolCall build(ObjectMapper mapper) {
            if (id == null || name == null) return null;
            try {
                Map<String, Object> args = argsJson.isEmpty()
                        ? new HashMap<>()
                        : mapper.readValue(argsJson.toString(), new TypeReference<>() {});
                return new ToolCall(id, name, args);
            } catch (Exception e) {
                return new ToolCall(id, name, new HashMap<>());
            }
        }
    }

    /**
     * Fires the call listener with the captured event. Wraps the call in
     * try/catch — a broken listener must never propagate into the chat path.
     */
    private void fireListener(LlmCallListener listener,
                               Instant startedAt, long start,
                               LlmConfig config, LlmRequest request,
                               String requestJson,
                               List<String> responseEvents,
                               LlmCallEvent.ResponseSummary response,
                               int promptTokens, int completionTokens,
                               String finishReason,
                               Integer errorStatus, String errorBody) {
        if (listener == null) return;
        long durationMs = System.currentTimeMillis() - start;
        try {
            listener.onCall(new LlmCallEvent(
                    startedAt, durationMs,
                    request.configName(), config.model(),
                    promptTokens, completionTokens, finishReason,
                    requestJson, responseEvents, response,
                    errorStatus, errorBody));
        } catch (Exception e) {
            log.warn("LlmCallListener threw — swallowing to keep chat alive: {}", e.getMessage());
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

    private void logWireRequest(ObjectNode requestNode) {
        if (!wire.isDebugEnabled()) return;
        try {
            wire.debug("→ stream request:\n{}", prettyWriter.writeValueAsString(requestNode));
        } catch (Exception e) {
            wire.debug("→ stream request: <serialise failed: {}>", e.getMessage());
        }
    }

    private ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        boolean reasoning = OpenAiModels.isReasoningModel(config.model());
        if (!reasoning) {
            root.put("temperature", request.temperature() < 0 ? config.defaultTemperature() : request.temperature());
        }
        int maxTokens = request.maxOutputTokens() < 0 ? config.maxOutputTokens() : request.maxOutputTokens();
        // o-series models require max_completion_tokens; all others use max_tokens
        root.put(reasoning ? "max_completion_tokens" : "max_tokens", maxTokens);

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
}
