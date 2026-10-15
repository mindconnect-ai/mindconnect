package ai.mindconnect.llm.adapter.anthropic;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
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
public class ClaudeGateway extends SseStreamingGateway {

    private static final Logger log = LoggerFactory.getLogger(ClaudeGateway.class);

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final MediaType JSON = MediaType.get("application/json");

    /** Placeholders resolve from the process environment alone — the library and desktop case. */
    public ClaudeGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        this(httpClient, objectMapper, encryption, EnvVarResolver.system());
    }

    /** @param env where {@code ${VAR}} placeholders in a config resolve from — on a server, the user's, the namespace's and the process's variables in that order */
    public ClaudeGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption,
                         EnvVarResolver env) {
        super("Anthropic", httpClient, objectMapper, encryption, env);
    }

    @Override
    protected Request httpRequest(LlmConfig config, String body) {
        return new Request.Builder()
                .url(LlmProvider.ANTHROPIC.baseUrlOr(config.baseUrl()) + "/v1/messages")
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();
    }

    @Override
    protected Stream newStream() {
        return new ClaudeStream();
    }

    /** One Messages stream: its tool-call builders and the type of each content block, known from its start event. */
    private final class ClaudeStream extends Stream {
        private final Map<Integer, ToolCallBuilder> builders = new TreeMap<>();
        private final List<String> blockTypes = new ArrayList<>();

        @Override
        protected StreamDelta parse(String block, Consumer<LlmStreamChunk> handler) {
            String dataLine = SseBlockReader.dataLine(block);
            if (dataLine == null) return StreamDelta.NONE;
            return parseClaudeEvent(SseBlockReader.eventType(block), dataLine, builders, handler, blockTypes);
        }

        @Override
        protected List<ToolCall> toolCalls() {
            return ToolCallBuilder.buildAll(builders.values(), objectMapper);
        }
    }

    private StreamDelta parseClaudeEvent(String eventType, String data,
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
                            return new StreamDelta(text, 1, null, 0, 0, false);
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
                    return new StreamDelta(null, 0, finish, 0, outputTokens, false);
                }
                case "message_start" -> {
                    JsonNode usage = root.path("message").path("usage");
                    int inputTokens = usage.isObject() ? usage.path("input_tokens").asInt(0) : 0;
                    return new StreamDelta(null, 0, null, inputTokens, 0, false);
                }
                default -> { /* ping, error, etc. — ignore */ }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Claude event: {} — payload: {}", e.getMessage(), data);
        }
        return StreamDelta.NONE;
    }

    private static FinishReason parseFinishReason(String raw) {
        return switch (raw) {
            case "end_turn" -> FinishReason.STOP;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.LENGTH;
            default -> FinishReason.ERROR;
        };
    }

    @Override
    protected ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        root.put("max_tokens", request.maxOutputTokens() < 0 ? config.maxOutputTokens() : request.maxOutputTokens());

        // Reasoning controls come from the generic per-config/per-request param map.
        // thinking: "adaptive" | "disabled" (default: omit → no thinking)
        // thinkingDisplay: "summarized" | "omitted" (default omitted by the API)
        // effort: low | medium | high | xhigh | max
        LlmParameterMap params = LlmParameterMap.of(config, request);
        String thinking = params.text("thinking");
        boolean thinkingEnabled = "adaptive".equalsIgnoreCase(thinking);
        if (thinkingEnabled) {
            ObjectNode thinkingNode = root.putObject("thinking");
            thinkingNode.put("type", "adaptive");
            String display = params.text("thinkingDisplay");
            if (display != null) thinkingNode.put("display", display);
        } else if ("disabled".equalsIgnoreCase(thinking)) {
            root.putObject("thinking").put("type", "disabled");
        }

        String effort = params.text("effort");
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
