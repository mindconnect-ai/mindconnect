package ai.mindconnect.llm.adapter.anthropic;

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
    private static final Logger wire = LoggerFactory.getLogger("ai.mindconnect.llm.wire");

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionHelper encryption;
    private final ObjectWriter prettyWriter;

    public ClaudeGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.encryption = encryption;
        this.prettyWriter = objectMapper.writerWithDefaultPrettyPrinter();
    }

    @Override
    public void chatStreaming(LlmConfig config, LlmRequest request,
                              Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        config = config.resolved(encryption);
        int msgCount = request.messages() == null ? 0 : request.messages().size();
        int toolCount = request.tools() == null ? 0 : request.tools().size();
        log.debug("Claude stream → model={} messages={} tools={}", config.model(), msgCount, toolCount);
        long start = System.currentTimeMillis();
        Instant startedAt = Instant.ofEpochMilli(start);

        // Trace capture buffers — populated as the stream progresses.
        String prettyRequestJson = null;
        List<String> responseEvents = new ArrayList<>();
        StringBuilder assistantText = new StringBuilder();
        Map<Integer, ClaudeToolCallBuilder> toolCallBuilders = new TreeMap<>();
        Integer errorStatus = null;
        String errorBody = null;

        String body;
        try {
            ObjectNode requestNode = buildRequestNode(config, request);
            try { prettyRequestJson = prettyWriter.writeValueAsString(requestNode); } catch (Exception ignored) {}
            logWireRequest(requestNode);
            body = objectMapper.writeValueAsString(requestNode);
        } catch (IOException e) {
            fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                    responseEvents, null, 0, 0, null, null, e.getMessage());
            throw new RuntimeException("Failed to build Anthropic request", e);
        }

        String baseUrl = config.baseUrl() != null && !config.baseUrl().isBlank()
                ? config.baseUrl()
                : "https://api.anthropic.com";

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
        StringBuilder debugAccumulatedText = wire.isDebugEnabled() ? new StringBuilder() : null;
        int textTokenCount = 0;
        boolean cancelledClean = false;

        Call call = httpClient.newCall(httpRequest);
        cancellation.registerAbort(call::cancel);

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                errorStatus = response.code();
                errorBody = response.body() != null ? response.body().string() : "(no body)";
                log.warn("Claude stream error: HTTP {} — body: {}", errorStatus, errorBody);
                fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                        responseEvents, null, 0, 0, null, errorStatus, errorBody);
                // Transient errors (rate limit / overloaded) are surfaced as a
                // typed exception so the generic RetryingLlmGateway can back off
                // and retry; everything else fails fast.
                if (LlmTransientException.isTransient(errorStatus)) {
                    throw new LlmTransientException(errorStatus,
                            LlmTransientException.parseRetryAfterMillis(response.header("retry-after")),
                            "Anthropic stream error: " + errorStatus);
                }
                throw new RuntimeException("Anthropic stream error: " + errorStatus);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {

                // SSE block accumulator — Anthropic events are
                // "event: …\ndata: …\n\n", so we collect lines until a blank
                // line and store/parse the block as one trace entry.
                StringBuilder block = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancellation.isCancelled()) { cancelledClean = true; break; }
                    if (line.isEmpty()) {
                        if (block.length() > 0) {
                            String blockText = block.toString();
                            responseEvents.add(blockText);
                            String eventType = extractEventType(blockText);
                            String dataLine = extractDataLine(blockText);
                            if (dataLine != null) {
                                ClaudeParsed p = parseClaudeEvent(eventType, dataLine,
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
                            block.setLength(0);
                        }
                        continue;
                    }
                    if (block.length() > 0) block.append('\n');
                    block.append(line);
                }
                // Flush any trailing block missing its blank line.
                if (block.length() > 0 && !cancelledClean) {
                    responseEvents.add(block.toString());
                }
            }
        } catch (IOException e) {
            if (cancellation.isCancelled()) {
                log.debug("Claude stream cancelled by caller after {}ms", System.currentTimeMillis() - start);
                cancelledClean = true;
            } else {
                fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                        responseEvents, summary(assistantText, toolCallBuilders),
                        inputTokens, outputTokens,
                        finish != null ? finish.name() : null, errorStatus,
                        errorBody != null ? errorBody : e.getMessage());
                throw new RuntimeException("Failed to stream from Anthropic", e);
            }
        } catch (RuntimeException e) {
            fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                    responseEvents, summary(assistantText, toolCallBuilders),
                    inputTokens, outputTokens,
                    finish != null ? finish.name() : null, errorStatus, errorBody);
            throw e;
        }

        fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                responseEvents, summary(assistantText, toolCallBuilders),
                inputTokens, outputTokens,
                finish != null ? finish.name() : null, null, null);

        if (cancelledClean) {
            log.debug("Claude stream cancelled by caller after {}ms", System.currentTimeMillis() - start);
            return;
        }

        if (debugAccumulatedText != null && debugAccumulatedText.length() > 0) {
            wire.debug("← stream response (accumulated, {} text tokens):\n{}", textTokenCount, debugAccumulatedText);
        }
        log.debug("Claude stream ← finish={} text-tokens={} in={}t out={}t {}ms",
                finish, textTokenCount, inputTokens, outputTokens, System.currentTimeMillis() - start);

        handler.accept(new LlmStreamChunk.Done(finish, inputTokens, outputTokens));
    }

    private static String extractEventType(String block) {
        for (String line : block.split("\n")) {
            if (line.startsWith("event: ")) return line.substring(7).trim();
        }
        return "";
    }

    private static String extractDataLine(String block) {
        for (String line : block.split("\n")) {
            if (line.startsWith("data: ")) return line.substring(6).trim();
        }
        return null;
    }

    /** Per-event parse result. */
    private record ClaudeParsed(
            String text, int textDeltas, FinishReason finishReason,
            int inputTokens, int outputTokens) {}

    private ClaudeParsed parseClaudeEvent(String eventType, String data,
                                           Map<Integer, ClaudeToolCallBuilder> builders,
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
                        builders.computeIfAbsent(index, i -> new ClaudeToolCallBuilder()).feed(id, name, null);
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
                        builders.computeIfAbsent(index, i -> new ClaudeToolCallBuilder()).feed(null, null, argsFrag);
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
                                                  Map<Integer, ClaudeToolCallBuilder> builders) {
        String text = assistantText.length() == 0 ? null : assistantText.toString();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ClaudeToolCallBuilder b : builders.values()) {
            ToolCall built = b.build(objectMapper);
            if (built != null) toolCalls.add(built);
        }
        if (text == null && toolCalls.isEmpty()) return null;
        return new LlmCallEvent.ResponseSummary(text, toolCalls);
    }

    /** Reassembles a Claude tool call across content_block_start + delta events. */
    private static final class ClaudeToolCallBuilder {
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

    /** See {@code AbstractOpenAiGateway#fireListener} — same contract. */
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
            case "end_turn" -> FinishReason.STOP;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.LENGTH;
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
                // or Anthropic rejects the replayed turn with HTTP 400.
                if (msg.thinkingBlocks() != null) {
                    for (ThinkingBlock tb : msg.thinkingBlocks()) {
                        ObjectNode block = content.addObject();
                        if ("redacted_thinking".equals(tb.type())) {
                            block.put("type", "redacted_thinking");
                            block.put("data", tb.data());
                        } else {
                            block.put("type", "thinking");
                            block.put("thinking", tb.text() != null ? tb.text() : "");
                            if (tb.signature() != null) block.put("signature", tb.signature());
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
}
