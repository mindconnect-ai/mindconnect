package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.LlmCallEventBuilder;
import ai.mindconnect.llm.adapter.LlmHttpError;
import ai.mindconnect.llm.adapter.LlmWireLog;
import ai.mindconnect.llm.adapter.SseBlockReader;
import ai.mindconnect.llm.adapter.ToolCallBuilder;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmParameterMap;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * The endpoints (base URL and key) OpenAI refused a reasoning summary to — an organization
     * that is not verified gets HTTP 400 for any request that asks for one. The summary is on
     * by default, so without remembering that, every call of such a key failed once more.
     */
    private final Set<String> summariesRefused = ConcurrentHashMap.newKeySet();

    /** Thrown inside one attempt when OpenAI refuses the default summary; the call is made again without it. */
    private static final class SummaryRefused extends RuntimeException {
        SummaryRefused() {
            super("reasoning summary refused", null, false, false);
        }
    }

    @Override
    public void chatStreaming(LlmConfig config, LlmRequest request,
                              Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        LlmConfig resolved = config.resolved(env, encryption);
        String endpoint = resolved.provider().baseUrlOr(resolved.baseUrl()) + "|" + Objects.hashCode(resolved.apiKey());
        try {
            stream(resolved, request, handler, cancellation, listener, summariesRefused.contains(endpoint));
        } catch (SummaryRefused refused) {
            log.warn("OpenAI refused a reasoning summary for model {} (organization not verified?) — "
                    + "asking again without one; set additionalParams.reasoning_summary=none to skip the attempt",
                    resolved.model());
            summariesRefused.add(endpoint);
            stream(resolved, request, handler, cancellation, listener, true);
        }
    }

    private void stream(LlmConfig config, LlmRequest request,
                        Consumer<LlmStreamChunk> handler,
                        Cancellation cancellation,
                        LlmCallListener listener,
                        boolean omitDefaultSummary) {
        LlmCallEventBuilder trace = new LlmCallEventBuilder(config, request);
        log.debug("LLM stream (responses) → model={} messages={} tools={}", config.model(),
                request.messages() == null ? 0 : request.messages().size(),
                request.tools() == null ? 0 : request.tools().size());

        StreamState state = new StreamState();
        Integer errorStatus = null;
        String errorBody = null;

        String body;
        try {
            ObjectNode requestNode = buildRequestNode(config, request, omitDefaultSummary);
            trace.request(prettyWriter, requestNode);
            LlmWireLog.request(prettyWriter, requestNode);
            body = objectMapper.writeValueAsString(requestNode);
        } catch (IOException e) {
            report(listener, trace, state, null, e.getMessage());
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
                if (errorStatus == 400 && !omitDefaultSummary && defaultSummaryRequested(config, request)
                        && errorBody.toLowerCase(Locale.ROOT).contains("summar")) {
                    throw new SummaryRefused();
                }
                throw LlmHttpError.failure(log, "OpenAI Responses", config, errorStatus,
                        response.header("retry-after"), errorBody);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                SseBlockReader blocks = new SseBlockReader(reader, cancellation);
                String block;
                while ((block = blocks.next()) != null) {
                    onBlock(block, trace, state, handler);
                }
                cancelledClean = blocks.cancelled();
                if (blocks.trailing() != null) onBlock(blocks.trailing(), trace, state, handler);
            }
            // A connection a proxy closed cleanly ends the read like a finished response would.
            // Without a completed, incomplete or failed event nothing says the answer is whole —
            // reporting STOP stored half a reply as final.
            if (!cancelledClean && !state.terminal) {
                throw new LlmTransientException(502, 0,
                        "OpenAI Responses stream for model " + config.model() + " ended before the response completed");
            }
        } catch (SummaryRefused e) {
            throw e;
        } catch (IOException e) {
            if (call.isCanceled()) {
                cancelledClean = true;
            } else {
                report(listener, trace, state, errorStatus, errorBody != null ? errorBody : e.getMessage());
                throw new RuntimeException("Failed to stream from OpenAI Responses endpoint", e);
            }
        } catch (RuntimeException e) {
            report(listener, trace, state, errorStatus, errorBody != null ? errorBody : e.getMessage());
            throw e;
        }

        report(listener, trace, state, null, null);
        if (cancelledClean) {
            log.debug("LLM stream (responses) cancelled by caller after {}ms", trace.elapsedMillis());
            return;
        }
        FinishReason finish = state.finish();
        log.debug("LLM stream (responses) ← finish={} in={}t out={}t {}ms",
                finish, state.inputTokens, state.outputTokens, trace.elapsedMillis());
        handler.accept(new LlmStreamChunk.Done(finish, state.inputTokens, state.outputTokens));
    }

    private void onBlock(String block, LlmCallEventBuilder trace, StreamState state,
                         Consumer<LlmStreamChunk> handler) {
        trace.responseEvents().add(block);
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
        return buildRequestNode(config, request, false);
    }

    /** Whether the request asks for a summary nobody configured — the default the gateway may drop. */
    static boolean defaultSummaryRequested(LlmConfig config, LlmRequest request) {
        return OpenAiModelPolicy.isReasoningModel(config.model())
                && LlmParameterMap.of(config, request).text("reasoning_summary") == null;
    }

    /**
     * @param omitDefaultSummary leave out the summary the gateway asks for by default — for an
     *                           endpoint OpenAI refused one to; a configured summary is sent as set
     */
    ObjectNode buildRequestNode(LlmConfig config, LlmRequest request, boolean omitDefaultSummary) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        // Stateless: the history is ours and travels in every request.
        root.put("store", false);

        boolean reasoning = OpenAiModelPolicy.isReasoningModel(config.model());
        if (!reasoning) {
            root.put("temperature", request.temperature() < 0 ? config.defaultTemperature() : request.temperature());
        }
        int maxTokens = request.maxOutputTokens() < 0 ? config.maxOutputTokens() : request.maxOutputTokens();
        if (maxTokens > 0) root.put("max_output_tokens", maxTokens);

        if (reasoning) {
            LlmParameterMap params = LlmParameterMap.of(config, request);
            ObjectNode reasoningNode = root.putObject("reasoning");
            String effort = params.text("reasoning_effort");
            if (effort != null) reasoningNode.put("effort", effort);
            // A readable summary of the reasoning, shown in the chat as the thought —
            // "none" for an organization OpenAI refuses summaries to.
            String summary = params.text("reasoning_summary");
            if (summary == null) summary = omitDefaultSummary ? "none" : "auto";
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
                                .put("image_url", AbstractOpenAiGateway.dataUrl(i.mediaType(), i.base64()));
                        case LlmContent.Document d -> content.addObject().put("type", "input_file")
                                .put("filename", d.name())
                                .put("file_data", AbstractOpenAiGateway.dataUrl(d.mediaType(), d.base64()));
                    }
                }
            }
        }
    }

    // ── stream ──────────────────────────────────────────────────────────────

    /** What one call's stream has told so far. Package-private for tests. */
    static final class StreamState {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        /** Function calls whose arguments arrived as deltas — their {@code done} item repeats them. */
        final Set<Integer> argumentsStreamed = new HashSet<>();
        int inputTokens;
        int outputTokens;
        boolean incompleteForLength;
        /** A completed or incomplete event arrived — the response is over, not just the connection. */
        boolean terminal;

        /**
         * LENGTH before TOOL_CALLS: a response cut off at max_output_tokens may end in the
         * middle of a call's arguments, and running that call with the empty arguments its
         * broken JSON falls back to would act on a request the model never finished.
         */
        FinishReason finish() {
            if (incompleteForLength) return FinishReason.LENGTH;
            return toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
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
                        state.toolCalls.computeIfAbsent(index, i -> new ToolCallBuilder())
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
                state.toolCalls.computeIfAbsent(index, i -> new ToolCallBuilder())
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
                            state.toolCalls.computeIfAbsent(index, i -> new ToolCallBuilder())
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
                state.terminal = true;
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

    private void report(LlmCallListener listener, LlmCallEventBuilder trace, StreamState state,
                        Integer errorStatus, String errorBody) {
        trace.report(listener,
                LlmCallEventBuilder.summary(state.text, ToolCallBuilder.buildAll(state.toolCalls.values(), objectMapper)),
                state.inputTokens, state.outputTokens, state.finish().name(), errorStatus, errorBody);
    }
}
