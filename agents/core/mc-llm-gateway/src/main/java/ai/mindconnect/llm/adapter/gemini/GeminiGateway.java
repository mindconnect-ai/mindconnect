package ai.mindconnect.llm.adapter.gemini;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.SseBlockReader;
import ai.mindconnect.llm.adapter.SseStreamingGateway;
import ai.mindconnect.llm.adapter.StreamDelta;
import ai.mindconnect.llm.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
public class GeminiGateway extends SseStreamingGateway {

    private static final Logger log = LoggerFactory.getLogger(GeminiGateway.class);
    private static final MediaType JSON = MediaType.get("application/json");


    /** Placeholders resolve from the process environment alone — the library and desktop case. */
    public GeminiGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        this(httpClient, objectMapper, encryption, EnvVarResolver.system());
    }

    /** @param env where {@code ${VAR}} placeholders in a config resolve from — on a server, the user's, the namespace's and the process's variables in that order */
    public GeminiGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption,
                         EnvVarResolver env) {
        super("Gemini", httpClient, objectMapper, encryption, env);
    }

    @Override
    protected Request httpRequest(LlmConfig config, String body) {
        String url = LlmProvider.GOOGLE_GEMINI.baseUrlOr(config.baseUrl()) + "/v1beta/models/" + config.model()
                + ":streamGenerateContent?alt=sse&key=" + config.apiKey();
        return new Request.Builder().url(url).post(RequestBody.create(body, JSON)).build();
    }

    @Override
    protected Stream newStream() {
        return new GeminiStream();
    }

    /** One generateContent stream. Gemini sends a tool call whole, so the calls are collected, not built. */
    private final class GeminiStream extends Stream {
        private final List<ToolCall> captured = new ArrayList<>();

        @Override
        protected StreamDelta parse(String block, Consumer<LlmStreamChunk> handler) {
            String dataLine = SseBlockReader.dataLine(block);
            if (dataLine == null) return StreamDelta.NONE;
            return parseGeminiData(dataLine, captured, handler);
        }

        @Override
        protected List<ToolCall> toolCalls() {
            return new ArrayList<>(captured);
        }
    }

    private StreamDelta parseGeminiData(String data, List<ToolCall> capturedToolCalls,
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
            return new StreamDelta(textAcc.length() > 0 ? textAcc.toString() : null,
                    textDeltas, finish, inputTokens, outputTokens, false);
        } catch (Exception e) {
            log.warn("Failed to parse Gemini event: {} — payload: {}", e.getMessage(), data);
            return StreamDelta.NONE;
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

    @Override
    protected ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) throws IOException {
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
            } else if (msg.hasMedia()) {
                // Gemini is parts-shaped already: text parts as {text}, media
                // as {inline_data} with the base64 payload.
                turn.put("role", msg.role() == MessageRole.ASSISTANT ? "model" : "user");
                ArrayNode parts = turn.putArray("parts");
                for (LlmContent part : msg.parts()) {
                    ObjectNode node = parts.addObject();
                    switch (part) {
                        case LlmContent.Text t -> node.put("text", t.text());
                        case LlmContent.Image i -> inlineData(node, i.mediaType(), i.base64());
                        case LlmContent.Document d -> inlineData(node, d.mediaType(), d.base64());
                    }
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

    /** An {@code inline_data} part: the media's type and base64 payload. */
    private static void inlineData(ObjectNode part, String mediaType, String base64) {
        ObjectNode data = part.putObject("inline_data");
        data.put("mime_type", mediaType);
        data.put("data", base64);
    }
}
