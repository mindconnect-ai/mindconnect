package ai.mindconnect.llm.adapter.gemini;

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
import java.util.function.Consumer;

/**
 * Google Gemini adapter using the v1beta streamGenerateContent SSE endpoint.
 * <p>
 * Wire differences from the OpenAI format:
 * <ul>
 *   <li>Auth via {@code ?key=} query parameter — no Authorization header.</li>
 *   <li>Endpoint: {@code /v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}}</li>
 *   <li>System prompt is a top-level {@code systemInstruction} with a {@code parts} array.</li>
 *   <li>Assistant role is {@code model}, not {@code assistant}.</li>
 *   <li>Tool results are {@code functionResponse} parts in a {@code user} turn.</li>
 *   <li>Tool calls arrive as {@code functionCall} parts, not {@code tool_calls} deltas.</li>
 *   <li>Finish reason and token usage arrive in {@code candidates[0].finishReason}
 *       and {@code usageMetadata} on each chunk (last non-empty value wins).</li>
 * </ul>
 */
public class GeminiGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(GeminiGateway.class);
    private static final Logger wire = LoggerFactory.getLogger("ai.mindconnect.llm.wire");
    private static final MediaType JSON = MediaType.get("application/json");

    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionHelper encryption;
    private final ObjectWriter prettyWriter;

    public GeminiGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
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
        log.debug("Gemini stream → model={} messages={} tools={}", config.model(), msgCount, toolCount);
        long start = System.currentTimeMillis();
        Instant startedAt = Instant.ofEpochMilli(start);

        // Trace capture buffers — populated as the stream progresses.
        String prettyRequestJson = null;
        List<String> responseEvents = new ArrayList<>();
        StringBuilder assistantText = new StringBuilder();
        // Gemini sends tool calls atomically (not delta-fragmented), so we
        // collect fully-built ToolCalls directly instead of using a builder.
        List<ToolCall> capturedToolCalls = new ArrayList<>();
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
            throw new RuntimeException("Failed to build Gemini request", e);
        }

        String baseUrl = config.baseUrl() != null && !config.baseUrl().isBlank()
                ? config.baseUrl() : DEFAULT_BASE_URL;
        String url = baseUrl + "/v1beta/models/" + config.model()
                + ":streamGenerateContent?alt=sse&key=" + config.apiKey();

        Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .build();

        Call call = httpClient.newCall(httpRequest);
        cancellation.registerAbort(call::cancel);

        int inputTokens = 0;
        int outputTokens = 0;
        FinishReason finish = FinishReason.STOP;
        StringBuilder debugAccumulatedText = wire.isDebugEnabled() ? new StringBuilder() : null;
        int textTokenCount = 0;
        boolean cancelledClean = false;

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                errorStatus = response.code();
                errorBody = response.body() != null ? response.body().string() : "(no body)";
                log.warn("Gemini stream error: HTTP {} — body: {}", errorStatus, errorBody);
                // Transient errors (rate limit / overloaded) are typed so the
                // generic RetryingLlmGateway can back off and retry.
                if (LlmTransientException.isTransient(errorStatus)) {
                    throw new LlmTransientException(errorStatus,
                            LlmTransientException.parseRetryAfterMillis(response.header("retry-after")),
                            "Gemini stream error: " + errorStatus);
                }
                throw new RuntimeException("Gemini stream error: " + errorStatus);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {

                StringBuilder block = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancellation.isCancelled()) { cancelledClean = true; break; }
                    if (line.isEmpty()) {
                        if (block.length() > 0) {
                            String blockText = block.toString();
                            responseEvents.add(blockText);
                            String dataLine = extractDataLine(blockText);
                            if (dataLine != null) {
                                GeminiParsed p = parseGeminiData(dataLine, capturedToolCalls, handler);
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
                if (block.length() > 0 && !cancelledClean) {
                    responseEvents.add(block.toString());
                }
            }
        } catch (IOException e) {
            if (call.isCanceled()) {
                log.debug("Gemini stream cancelled by caller after {}ms", System.currentTimeMillis() - start);
                cancelledClean = true;
            } else {
                fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                        responseEvents, summary(assistantText, capturedToolCalls),
                        inputTokens, outputTokens,
                        finish != null ? finish.name() : null, errorStatus,
                        errorBody != null ? errorBody : e.getMessage());
                throw new RuntimeException("Failed to stream from Gemini", e);
            }
        } catch (RuntimeException e) {
            fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                    responseEvents, summary(assistantText, capturedToolCalls),
                    inputTokens, outputTokens,
                    finish != null ? finish.name() : null, errorStatus, errorBody);
            throw e;
        }

        fireListener(listener, startedAt, start, config, request, prettyRequestJson,
                responseEvents, summary(assistantText, capturedToolCalls),
                inputTokens, outputTokens,
                finish != null ? finish.name() : null, null, null);

        if (cancelledClean) {
            log.debug("Gemini stream cancelled by caller after {}ms", System.currentTimeMillis() - start);
            return;
        }

        if (debugAccumulatedText != null && debugAccumulatedText.length() > 0) {
            wire.debug("← stream response (accumulated, {} text tokens):\n{}", textTokenCount, debugAccumulatedText);
        }
        log.debug("Gemini stream ← finish={} text-tokens={} in={}t out={}t {}ms",
                finish, textTokenCount, inputTokens, outputTokens, System.currentTimeMillis() - start);

        handler.accept(new LlmStreamChunk.Done(finish, inputTokens, outputTokens));
    }

    private static String extractDataLine(String block) {
        for (String line : block.split("\n")) {
            if (line.startsWith("data: ")) return line.substring(6).trim();
        }
        return null;
    }

    private record GeminiParsed(
            String text, int textDeltas, FinishReason finishReason,
            int inputTokens, int outputTokens) {}

    private GeminiParsed parseGeminiData(String data, List<ToolCall> capturedToolCalls,
                                          Consumer<LlmStreamChunk> handler) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode candidate = root.path("candidates").path(0);
            JsonNode content = candidate.path("content");
            JsonNode parts = content.path("parts");

            StringBuilder textAcc = new StringBuilder();
            int textDeltas = 0;
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (part.has("text")) {
                        String text = part.path("text").asText("");
                        if (!text.isEmpty()) {
                            handler.accept(new LlmStreamChunk.TextDelta(text));
                            textAcc.append(text);
                            textDeltas++;
                        }
                    } else if (part.has("functionCall")) {
                        JsonNode fc = part.path("functionCall");
                        int index = capturedToolCalls.size();
                        String name = fc.path("name").asText(null);
                        // Generate a stable id since Gemini doesn't supply one;
                        // downstream tooling needs a tool_call_id for pairing.
                        String id = "gemini-call-" + index;
                        Map<String, Object> args;
                        try {
                            args = objectMapper.convertValue(fc.path("args"), new TypeReference<>() {});
                            if (args == null) args = new HashMap<>();
                        } catch (Exception e) {
                            args = new HashMap<>();
                        }
                        // Gemini 2.5+ attaches a thoughtSignature to the part; it
                        // MUST be echoed back when this call is replayed in history,
                        // or the next request fails with HTTP 400.
                        String thoughtSignature = part.path("thoughtSignature").asText(null);
                        String argsJson = objectMapper.writeValueAsString(fc.path("args"));
                        handler.accept(new LlmStreamChunk.ToolCallDelta(index, id, name, null, thoughtSignature));
                        handler.accept(new LlmStreamChunk.ToolCallDelta(index, null, null, argsJson, null));
                        capturedToolCalls.add(new ToolCall(id, name, args, thoughtSignature));
                    }
                }
            }

            FinishReason finish = null;
            String finishRaw = candidate.path("finishReason").asText(null);
            if (finishRaw != null && !finishRaw.isEmpty() && !"null".equals(finishRaw)) {
                finish = parseFinishReason(finishRaw);
            }

            int inputTokens = 0;
            int outputTokens = 0;
            JsonNode usage = root.path("usageMetadata");
            if (usage.isObject()) {
                inputTokens = usage.path("promptTokenCount").asInt(0);
                outputTokens = usage.path("candidatesTokenCount").asInt(0);
            }
            return new GeminiParsed(textAcc.length() > 0 ? textAcc.toString() : null,
                    textDeltas, finish, inputTokens, outputTokens);
        } catch (Exception e) {
            log.warn("Failed to parse Gemini event: {} — payload: {}", e.getMessage(), data);
            return new GeminiParsed(null, 0, null, 0, 0);
        }
    }

    private LlmCallEvent.ResponseSummary summary(StringBuilder assistantText, List<ToolCall> toolCalls) {
        String text = assistantText.length() == 0 ? null : assistantText.toString();
        if (text == null && toolCalls.isEmpty()) return null;
        return new LlmCallEvent.ResponseSummary(text, new ArrayList<>(toolCalls));
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
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS" -> FinishReason.LENGTH;
            // Gemini signals tool use via parts, not a distinct finish reason,
            // but guard against future API changes.
            case "TOOL_CALL" -> FinishReason.TOOL_CALLS;
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
     * Builds the Gemini generateContent request body.
     * <p>
     * Key differences from OpenAI:
     * <ul>
     *   <li>SYSTEM messages become the top-level {@code systemInstruction} field.</li>
     *   <li>ASSISTANT role maps to {@code model}.</li>
     *   <li>TOOL results become {@code user} turns with {@code functionResponse} parts.</li>
     *   <li>ASSISTANT tool calls become {@code model} turns with {@code functionCall} parts.</li>
     *   <li>Tool definitions use {@code functionDeclarations} under a {@code tools} wrapper.</li>
     * </ul>
     */
    private ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();

        // Generation config
        ObjectNode genConfig = root.putObject("generationConfig");
        genConfig.put("temperature", request.temperature() < 0 ? config.defaultTemperature() : request.temperature());
        genConfig.put("maxOutputTokens", request.maxOutputTokens() < 0 ? config.maxOutputTokens() : request.maxOutputTokens());

        // Extract system message
        List<LlmMessage> nonSystemMessages = new ArrayList<>();
        for (LlmMessage msg : request.messages()) {
            if (msg.role() == MessageRole.SYSTEM) {
                ObjectNode sysInstruction = root.putObject("systemInstruction");
                ArrayNode parts = sysInstruction.putArray("parts");
                parts.addObject().put("text", msg.content() != null ? msg.content() : "");
            } else {
                nonSystemMessages.add(msg);
            }
        }

        ArrayNode contents = root.putArray("contents");
        for (LlmMessage msg : nonSystemMessages) {
            ObjectNode turn = contents.addObject();

            if (msg.role() == MessageRole.TOOL) {
                // Tool result → user turn with functionResponse part
                turn.put("role", "user");
                ArrayNode parts = turn.putArray("parts");
                ObjectNode part = parts.addObject();
                ObjectNode funcResp = part.putObject("functionResponse");
                funcResp.put("name", msg.toolCallId()); // Gemini uses name, not id
                funcResp.putObject("response").put("content", msg.content() != null ? msg.content() : "");
            } else if (msg.role() == MessageRole.ASSISTANT
                    && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                // Assistant tool calls → model turn with functionCall parts
                turn.put("role", "model");
                ArrayNode parts = turn.putArray("parts");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode part = parts.addObject();
                    // Echo back the thoughtSignature Gemini gave us; required by
                    // Gemini 2.5+ for replayed functionCall parts to be accepted.
                    if (tc.thoughtSignature() != null && !tc.thoughtSignature().isBlank()) {
                        part.put("thoughtSignature", tc.thoughtSignature());
                    }
                    ObjectNode funcCall = part.putObject("functionCall");
                    funcCall.put("name", tc.name());
                    funcCall.set("args", objectMapper.valueToTree(tc.arguments()));
                }
            } else {
                turn.put("role", msg.role() == MessageRole.ASSISTANT ? "model" : "user");
                ArrayNode parts = turn.putArray("parts");
                parts.addObject().put("text", msg.content() != null ? msg.content() : "");
            }
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            ObjectNode toolObj = toolsNode.addObject();
            ArrayNode declarations = toolObj.putArray("functionDeclarations");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode decl = declarations.addObject();
                decl.put("name", tool.name());
                decl.put("description", tool.description());
                decl.set("parameters", objectMapper.valueToTree(tool.parametersSchema()));
            }
        }

        return root;
    }
}
