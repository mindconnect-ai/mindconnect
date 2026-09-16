package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.LlmHttpErrors;
import ai.mindconnect.llm.adapter.TraceRedaction;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmCallEvent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmParams;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.LlmTransientException;
import ai.mindconnect.llm.domain.MessageRole;
import ai.mindconnect.llm.domain.ThinkingBlock;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * OpenAI's own API through the <b>Responses API</b> ({@code /v1/responses})
 * instead of Chat Completions. From gpt-5.6 on, OpenAI refuses function tools
 * together with reasoning on Chat Completions; only the Responses API takes
 * both. Every other OpenAI-compatible server stays on
 * {@link OpenAiCompatibleGateway}.
 *
 * <p>Stateless: {@code store=false}, the history travels in every request as
 * {@code input} items, so memory strategies, compaction and regenerate work
 * as with any other provider. What a reasoning model thought before its tool
 * calls must travel with it: the gateway asks for
 * {@code reasoning.encrypted_content} and hands each reasoning item on as a
 * {@link ThinkingBlock} of type {@value #REASONING} — the summary as its
 * text, the encrypted content as its data. The runtime stores it with the
 * tool calls, and the next request replays it in front of them.
 *
 * <p>Stream events map onto the port's chunks: reasoning summary deltas
 * become {@link LlmStreamChunk.ThinkingDelta}s, {@code output_text} deltas
 * {@link LlmStreamChunk.TextDelta}s, function calls
 * {@link LlmStreamChunk.ToolCallDelta}s — each grouped by the item's
 * {@code output_index}.
 */
public class OpenAiResponsesGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesGateway.class);
    private static final MediaType JSON = MediaType.get("application/json");

    /** The {@link ThinkingBlock#type()} of an OpenAI reasoning item. */
    static final String REASONING = "reasoning";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionHelper encryption;
    private final EnvVarResolver env;
    private final ObjectWriter prettyWriter;

    /** Placeholders resolve from the process environment alone — the library and desktop case. */
    public OpenAiResponsesGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption) {
        this(httpClient, objectMapper, encryption, EnvVarResolver.system());
    }

    /** @param env where {@code ${VAR}} placeholders in a config resolve from */
    public OpenAiResponsesGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption,
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
        long start = System.currentTimeMillis();
        Instant startedAt = Instant.ofEpochMilli(start);
        log.debug("LLM stream (responses) → model={} messages={} tools={}", config.model(),
                request.messages() == null ? 0 : request.messages().size(),
                request.tools() == null ? 0 : request.tools().size());

        String prettyRequestJson = null;
        StreamState state = new StreamState();
        Integer errorStatus = null;
        String errorBody = null;

        String body;
        try {
            ObjectNode requestNode = buildRequestNode(config, request);
            try { prettyRequestJson = prettyWriter.writeValueAsString(TraceRedaction.redactMedia(requestNode)); } catch (Exception ignored) {}
            if (AbstractOpenAiGateway.wire.isDebugEnabled()) {
                AbstractOpenAiGateway.wire.debug("→ responses request:\n{}", prettyRequestJson);
            }
            body = objectMapper.writeValueAsString(requestNode);
        } catch (IOException e) {
            fireListener(listener, startedAt, start, config, request, prettyRequestJson, state, null, e.getMessage());
            throw new RuntimeException("Failed to build OpenAI Responses request", e);
        }

        Request httpRequest = new Request.Builder()
                .url(config.provider().baseUrlOr(config.baseUrl()) + "/v1/responses")
                .header("Authorization", "Bearer " + config.apiKey())
                .post(RequestBody.create(body, JSON))
                .build();
        Call call = httpClient.newCall(httpRequest);
        cancellation.registerAbort(call::cancel);
        boolean cancelledClean = false;

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                errorStatus = response.code();
                errorBody = response.body() != null ? response.body().string() : "(no body)";
                LlmHttpErrors.logHttpError(log, "OpenAI Responses", config, errorStatus,
                        response.header("retry-after"), errorBody);
                if (LlmTransientException.isTransient(errorStatus)) {
                    long retryAfterMillis = LlmTransientException.parseRetryAfterMillis(response.header("retry-after"));
                    throw new LlmTransientException(errorStatus, retryAfterMillis,
                            LlmHttpErrors.transientMessage("OpenAI Responses", config, errorStatus, retryAfterMillis));
                }
                throw new RuntimeException("OpenAI Responses stream error: " + errorStatus);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                StringBuilder block = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancellation.isCancelled()) { cancelledClean = true; break; }
                    if (!line.isEmpty()) {
                        if (block.length() > 0) block.append('\n');
                        block.append(line);
                        continue;
                    }
                    if (block.length() > 0) {
                        onBlock(block.toString(), state, handler);
                        block.setLength(0);
                    }
                }
                if (block.length() > 0 && !cancelledClean) onBlock(block.toString(), state, handler);
            }
        } catch (IOException e) {
            if (call.isCanceled()) {
                cancelledClean = true;
            } else {
                fireListener(listener, startedAt, start, config, request, prettyRequestJson, state,
                        errorStatus, errorBody != null ? errorBody : e.getMessage());
                throw new RuntimeException("Failed to stream from OpenAI Responses endpoint", e);
            }
        } catch (RuntimeException e) {
            fireListener(listener, startedAt, start, config, request, prettyRequestJson, state,
                    errorStatus, errorBody != null ? errorBody : e.getMessage());
            throw e;
        }

        fireListener(listener, startedAt, start, config, request, prettyRequestJson, state, null, null);
        if (cancelledClean) {
            log.debug("LLM stream (responses) cancelled by caller after {}ms", System.currentTimeMillis() - start);
            return;
        }
        FinishReason finish = state.finish();
        log.debug("LLM stream (responses) ← finish={} in={}t out={}t {}ms",
                finish, state.inputTokens, state.outputTokens, System.currentTimeMillis() - start);
        handler.accept(new LlmStreamChunk.Done(finish, state.inputTokens, state.outputTokens));
    }

    private void onBlock(String block, StreamState state, Consumer<LlmStreamChunk> handler) {
        state.responseEvents.add(block);
        for (String l : block.split("\n")) {
            if (l.startsWith("data: ")) {
                String data = l.substring(6).trim();
                if (!data.isEmpty() && !"[DONE]".equals(data)) parseEvent(data, state, handler);
                return;
            }
        }
    }

    // ── request ─────────────────────────────────────────────────────────────

    /** The {@code POST /v1/responses} body. Package-private for tests of the wire JSON. */
    ObjectNode buildRequestNode(LlmConfig config, LlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        // Stateless: the history is ours and travels in every request.
        root.put("store", false);

        boolean reasoning = OpenAiModels.isReasoningModel(config.model());
        if (!reasoning) {
            root.put("temperature", request.temperature() < 0 ? config.defaultTemperature() : request.temperature());
        }
        int maxTokens = request.maxOutputTokens() < 0 ? config.maxOutputTokens() : request.maxOutputTokens();
        if (maxTokens > 0) root.put("max_output_tokens", maxTokens);

        if (reasoning) {
            Map<String, Object> params = LlmParams.merge(config, request);
            ObjectNode reasoningNode = root.putObject("reasoning");
            String effort = LlmParams.string(params, "reasoning_effort");
            if (effort != null) reasoningNode.put("effort", effort);
            // A readable summary of the reasoning, shown in the chat as the thought —
            // "none" for an organization OpenAI refuses summaries to.
            String summary = LlmParams.string(params, "reasoning_summary");
            if (summary == null) summary = "auto";
            if (!"none".equals(summary)) reasoningNode.put("summary", summary);
            // What the model thought must go back with its tool calls; with
            // store=false only the encrypted content can carry it.
            root.putArray("include").add("reasoning.encrypted_content");
        }

        ArrayNode input = root.putArray("input");
        for (LlmMessage msg : request.messages()) {
            renderMessage(input, msg);
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                t.put("name", tool.name());
                t.put("description", tool.description());
                t.set("parameters", objectMapper.valueToTree(tool.parametersSchema()));
                // Our schemas are not written for strict mode (all properties required, no extras).
                t.put("strict", false);
            }
        }
        return root;
    }

    private void renderMessage(ArrayNode input, LlmMessage msg) {
        switch (msg.role()) {
            case TOOL -> input.addObject()
                    .put("type", "function_call_output")
                    .put("call_id", msg.toolCallId())
                    .put("output", msg.content() != null ? msg.content() : "");
            case ASSISTANT -> {
                // Reasoning first — it belongs in front of the calls it led to.
                // Only OpenAI's own items go back; another provider's thinking
                // (the conversation switched configs) means nothing here.
                if (msg.thinkingBlocks() != null) {
                    for (ThinkingBlock tb : msg.thinkingBlocks()) {
                        if (!REASONING.equals(tb.type()) || tb.data() == null) continue;
                        ObjectNode item = input.addObject();
                        item.put("type", "reasoning");
                        ArrayNode summary = item.putArray("summary");
                        if (tb.text() != null && !tb.text().isBlank()) {
                            summary.addObject().put("type", "summary_text").put("text", tb.text());
                        }
                        item.put("encrypted_content", tb.data());
                    }
                }
                String text = msg.content();
                if (text != null && !text.isEmpty()) {
                    input.addObject().put("role", "assistant").put("content", text);
                }
                if (msg.toolCalls() != null) {
                    for (ToolCall tc : msg.toolCalls()) {
                        String arguments;
                        try {
                            arguments = objectMapper.writeValueAsString(tc.arguments() != null ? tc.arguments() : Map.of());
                        } catch (IOException e) {
                            arguments = "{}";
                        }
                        input.addObject()
                                .put("type", "function_call")
                                .put("call_id", tc.id())
                                .put("name", tc.name())
                                .put("arguments", arguments);
                    }
                }
            }
            default -> {
                String role = msg.role() == MessageRole.SYSTEM ? "system" : "user";
                if (!msg.hasMedia()) {
                    input.addObject().put("role", role).put("content", msg.content() != null ? msg.content() : "");
                    return;
                }
                ObjectNode item = input.addObject().put("role", role);
                ArrayNode content = item.putArray("content");
                for (LlmContent part : msg.parts()) {
                    switch (part) {
                        case LlmContent.Text t -> content.addObject().put("type", "input_text").put("text", t.text());
                        case LlmContent.Image i -> content.addObject().put("type", "input_image")
                                .put("image_url", dataUrl(i.mediaType(), i.base64()));
                        case LlmContent.Document d -> content.addObject().put("type", "input_file")
                                .put("filename", d.name())
                                .put("file_data", dataUrl(d.mediaType(), d.base64()));
                    }
                }
            }
        }
    }

    private static String dataUrl(String mediaType, String base64) {
        return "data:" + mediaType + ";base64," + base64;
    }

    // ── stream ──────────────────────────────────────────────────────────────

    /** What one call's stream has told so far. Package-private for tests. */
    static final class StreamState {
        final List<String> responseEvents = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        final Map<Integer, AbstractOpenAiGateway.OpenAiToolCallBuilder> toolCalls = new TreeMap<>();
        /** Function calls whose arguments arrived as deltas — their {@code done} item repeats them. */
        final Set<Integer> argumentsStreamed = new HashSet<>();
        int inputTokens;
        int outputTokens;
        boolean incompleteForLength;

        FinishReason finish() {
            if (!toolCalls.isEmpty()) return FinishReason.TOOL_CALLS;
            return incompleteForLength ? FinishReason.LENGTH : FinishReason.STOP;
        }
    }

    /** Parses one SSE data payload and forwards what it says. Package-private for tests. */
    void parseEvent(String data, StreamState state, Consumer<LlmStreamChunk> handler) {
        JsonNode event;
        try {
            event = objectMapper.readTree(data);
        } catch (IOException e) {
            log.warn("Failed to parse OpenAI Responses event: {} — payload: {}", e.getMessage(), data);
            return;
        }
        int index = event.path("output_index").asInt(0);
        switch (event.path("type").asText("")) {
            case "response.output_item.added" -> {
                JsonNode item = event.path("item");
                switch (item.path("type").asText("")) {
                    case "reasoning" -> handler.accept(
                            new LlmStreamChunk.ThinkingDelta(index, REASONING, null, null, null));
                    case "function_call" -> {
                        String callId = item.path("call_id").asText(null);
                        String name = item.path("name").asText(null);
                        handler.accept(new LlmStreamChunk.ToolCallDelta(index, callId, name, null));
                        state.toolCalls.computeIfAbsent(index, i -> new AbstractOpenAiGateway.OpenAiToolCallBuilder())
                                .feed(callId, name, null);
                    }
                    default -> { }
                }
            }
            case "response.reasoning_summary_part.added" -> {
                // Parts of one summary read as paragraphs.
                if (event.path("summary_index").asInt(0) > 0) {
                    handler.accept(new LlmStreamChunk.ThinkingDelta(index, null, "\n\n", null, null));
                }
            }
            case "response.reasoning_summary_text.delta" -> {
                String delta = event.path("delta").asText("");
                if (!delta.isEmpty()) handler.accept(new LlmStreamChunk.ThinkingDelta(index, null, delta, null, null));
            }
            case "response.output_text.delta", "response.refusal.delta" -> {
                String delta = event.path("delta").asText("");
                if (!delta.isEmpty()) {
                    state.text.append(delta);
                    handler.accept(new LlmStreamChunk.TextDelta(delta));
                }
            }
            case "response.function_call_arguments.delta" -> {
                String delta = event.path("delta").asText("");
                if (delta.isEmpty()) return;
                state.argumentsStreamed.add(index);
                handler.accept(new LlmStreamChunk.ToolCallDelta(index, null, null, delta));
                state.toolCalls.computeIfAbsent(index, i -> new AbstractOpenAiGateway.OpenAiToolCallBuilder())
                        .feed(null, null, delta);
            }
            case "response.output_item.done" -> {
                JsonNode item = event.path("item");
                switch (item.path("type").asText("")) {
                    case "reasoning" -> {
                        String encrypted = item.path("encrypted_content").asText(null);
                        if (encrypted != null) {
                            handler.accept(new LlmStreamChunk.ThinkingDelta(index, null, null, null, encrypted));
                        }
                    }
                    case "function_call" -> {
                        // Arguments that never came as deltas arrive whole here.
                        String arguments = item.path("arguments").asText("");
                        if (!state.argumentsStreamed.contains(index) && !arguments.isEmpty()) {
                            handler.accept(new LlmStreamChunk.ToolCallDelta(index, null, null, arguments));
                            state.toolCalls.computeIfAbsent(index, i -> new AbstractOpenAiGateway.OpenAiToolCallBuilder())
                                    .feed(null, null, arguments);
                        }
                    }
                    default -> { }
                }
            }
            case "response.completed", "response.incomplete" -> {
                JsonNode response = event.path("response");
                JsonNode usage = response.path("usage");
                state.inputTokens = usage.path("input_tokens").asInt(0);
                state.outputTokens = usage.path("output_tokens").asInt(0);
                state.incompleteForLength = "max_output_tokens".equals(
                        response.path("incomplete_details").path("reason").asText(null));
            }
            case "response.failed" -> throw new RuntimeException("LLM stream reported an error: "
                    + errorMessage(event.path("response").path("error")));
            case "error" -> throw new RuntimeException("LLM stream reported an error: "
                    + errorMessage(event.has("error") ? event.path("error") : event));
            default -> { }
        }
    }

    private static String errorMessage(JsonNode error) {
        return error.hasNonNull("message") ? error.get("message").asText() : error.toString();
    }

    // ── trace ───────────────────────────────────────────────────────────────

    private void fireListener(LlmCallListener listener, Instant startedAt, long start,
                              LlmConfig config, LlmRequest request, String requestJson,
                              StreamState state, Integer errorStatus, String errorBody) {
        if (listener == null) return;
        try {
            List<ToolCall> toolCalls = new ArrayList<>();
            for (AbstractOpenAiGateway.OpenAiToolCallBuilder b : state.toolCalls.values()) {
                ToolCall built = b.build(objectMapper);
                if (built != null) toolCalls.add(built);
            }
            String text = state.text.isEmpty() ? null : state.text.toString();
            LlmCallEvent.ResponseSummary summary = text == null && toolCalls.isEmpty()
                    ? null : new LlmCallEvent.ResponseSummary(text, toolCalls);
            listener.onCall(new LlmCallEvent(
                    startedAt, System.currentTimeMillis() - start,
                    request.configName(), config.model(),
                    state.inputTokens, state.outputTokens, state.finish().name(),
                    requestJson, state.responseEvents, summary,
                    errorStatus, errorBody));
        } catch (Exception e) {
            log.warn("LlmCallListener threw — swallowing to keep chat alive: {}", e.getMessage());
        }
    }
}
