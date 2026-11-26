package ai.mindconnect.llm.demo.adapter.openaisdk;

import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.FinishReason;
import ai.mindconnect.llm.runtime.api.domain.LlmCallEvent;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.domain.LlmTransientException;
import ai.mindconnect.llm.runtime.api.domain.ToolCall;
import ai.mindconnect.llm.runtime.api.domain.ToolDefinition;
import ai.mindconnect.llm.runtime.api.port.out.LlmCallListener;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A provider of one's own: chat completions through the official OpenAI Java SDK,
 * behind the gateway's {@link LlmGateway} SPI. Text and tool calls only; a message
 * carrying images or documents is refused.
 *
 * <p>The SDK hides the wire, so the call event carries the request body as the SDK
 * serialises it and one JSON line per streamed chunk instead of the raw SSE blocks.
 * Rate limits and server errors become {@link LlmTransientException}, so the
 * gateway's retry decorator can wait and try again.
 */
public final class OpenAiSdkGateway implements LlmGateway {

    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() { };

    /** One client per endpoint and key: each holds its own connection pool. */
    private final Map<String, OpenAIClient> clients = new ConcurrentHashMap<>();

    @Override
    public void chatStreaming(LlmConfig config, LlmRequest request, Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation, LlmCallListener listener) {
        Instant started = Instant.now();
        ChatCompletionCreateParams params = params(config, request);
        Call call = new Call(config, started, json(params._body()));
        try (StreamResponse<ChatCompletionChunk> stream = client(config).chat().completions().createStreaming(params)) {
            Cancellation.Registration abort = cancellation.registerAbort(stream::close);
            try {
                stream.stream().forEach(chunk -> call.accept(chunk, handler));
            } finally {
                abort.unregister();
            }
            if (cancellation.isCancelled()) {
                return;
            }
            handler.accept(new LlmStreamChunk.Done(call.finishReason(), call.inputTokens, call.outputTokens));
            notify(listener, call.event(null, null));
        } catch (OpenAIServiceException e) {
            notify(listener, call.event(e.statusCode(), e.getMessage()));
            if (e.statusCode() == 429 || e.statusCode() >= 500) {
                throw new LlmTransientException(e.statusCode(), 0, e.getMessage());
            }
            throw e;
        } catch (RuntimeException e) {
            if (cancellation.isCancelled()) {
                return; // closing the stream to cancel makes the read fail; that is the point
            }
            notify(listener, call.event(null, e.getMessage()));
            throw e;
        }
    }

    private OpenAIClient client(LlmConfig config) {
        String baseUrl = config.provider().baseUrlOr(config.baseUrl()) + "/v1";
        return clients.computeIfAbsent(baseUrl + " " + config.apiKey(), key ->
                OpenAIOkHttpClient.builder().baseUrl(baseUrl).apiKey(config.apiKey())
                        // Retrying is the gateway's business, by the config's RetryConfig; the SDK must not do it twice.
                        .maxRetries(0)
                        .build());
    }

    private static ChatCompletionCreateParams params(LlmConfig config, LlmRequest request) {
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(config.model())
                .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        // Reasoning models reject a temperature, so only one the caller asked for is sent.
        if (request.temperature() >= 0) {
            params.temperature(request.temperature());
        }
        int maxTokens = request.maxOutputTokens() > 0 ? request.maxOutputTokens() : config.maxOutputTokens();
        if (maxTokens > 0) {
            params.maxCompletionTokens(maxTokens);
        }
        request.messages().forEach(message -> addMessage(params, message));
        if (request.tools() != null) {
            request.tools().forEach(tool -> params.addFunctionTool(function(tool)));
        }
        return params.build();
    }

    private static void addMessage(ChatCompletionCreateParams.Builder params, LlmMessage message) {
        if (message.hasMedia()) {
            throw new IllegalArgumentException("This provider sends text only, not " + message);
        }
        switch (message.role()) {
            case SYSTEM -> params.addSystemMessage(message.content());
            case USER -> params.addUserMessage(message.content());
            case ASSISTANT -> params.addMessage(assistant(message));
            case TOOL -> params.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(message.toolCallId()).content(message.content()).build());
        }
    }

    private static ChatCompletionAssistantMessageParam assistant(LlmMessage message) {
        ChatCompletionAssistantMessageParam.Builder assistant = ChatCompletionAssistantMessageParam.builder();
        if (message.content() != null) {
            assistant.content(message.content());
        }
        if (message.toolCalls() != null) {
            message.toolCalls().forEach(call -> assistant.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                    .id(call.id())
                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(call.name()).arguments(json(call.arguments())).build())
                    .build()));
        }
        return assistant.build();
    }

    private static FunctionDefinition function(ToolDefinition tool) {
        Map<String, JsonValue> schema = new LinkedHashMap<>();
        tool.parametersSchema().forEach((key, value) -> schema.put(key, JsonValue.from(value)));
        return FunctionDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(FunctionParameters.builder().putAllAdditionalProperties(schema).build())
                .build();
    }

    private static void notify(LlmCallListener listener, LlmCallEvent event) {
        try {
            listener.onCall(event);
        } catch (RuntimeException ignored) {
            // The SPI's promise: a broken trace never tears down the chat.
        }
    }

    /** The SDK's own mapper: it knows the SDK's types, and this provider brings no other. */
    private static String json(Object value) {
        try {
            return mapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write " + value + " as JSON", e);
        }
    }

    private static JsonMapper mapper() {
        return ObjectMappers.jsonMapper();
    }

    /** What one call has seen so far: the deltas reassembled, the usage, the finish reason. */
    private static final class Call {

        private final LlmConfig config;
        private final Instant started;
        private final String requestJson;
        private final List<String> events = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final Map<Long, PendingToolCall> toolCalls = new LinkedHashMap<>();
        private ChatCompletionChunk.Choice.FinishReason finish;
        private int inputTokens;
        private int outputTokens;

        Call(LlmConfig config, Instant started, String requestJson) {
            this.config = config;
            this.started = started;
            this.requestJson = requestJson;
        }

        void accept(ChatCompletionChunk chunk, Consumer<LlmStreamChunk> handler) {
            events.add(json(chunk));
            chunk.usage().ifPresent(usage -> {
                inputTokens = (int) usage.promptTokens();
                outputTokens = (int) usage.completionTokens();
            });
            for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                choice.delta().content().filter(s -> !s.isEmpty()).ifPresent(s -> {
                    text.append(s);
                    handler.accept(new LlmStreamChunk.TextDelta(s));
                });
                choice.delta().toolCalls().ifPresent(deltas -> deltas.forEach(delta -> {
                    String name = delta.function().flatMap(f -> f.name()).orElse(null);
                    String arguments = delta.function().flatMap(f -> f.arguments()).orElse(null);
                    toolCalls.computeIfAbsent(delta.index(), i -> new PendingToolCall())
                            .add(delta.id().orElse(null), name, arguments);
                    handler.accept(new LlmStreamChunk.ToolCallDelta((int) delta.index(),
                            delta.id().orElse(null), name, arguments));
                }));
                choice.finishReason().ifPresent(reason -> finish = reason);
            }
        }

        FinishReason finishReason() {
            if (finish == null || finish.equals(ChatCompletionChunk.Choice.FinishReason.STOP)) {
                return FinishReason.STOP;
            }
            if (finish.equals(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS)) {
                return FinishReason.TOOL_CALLS;
            }
            if (finish.equals(ChatCompletionChunk.Choice.FinishReason.LENGTH)) {
                return FinishReason.LENGTH;
            }
            return FinishReason.ERROR;
        }

        LlmCallEvent event(Integer errorStatus, String errorBody) {
            List<ToolCall> calls = toolCalls.values().stream().map(PendingToolCall::toToolCall).toList();
            boolean answered = !text.isEmpty() || !calls.isEmpty();
            return new LlmCallEvent(started, Duration.between(started, Instant.now()).toMillis(),
                    config.name(), config.model(), inputTokens, outputTokens,
                    finish == null ? null : finish.asString(), requestJson, List.copyOf(events),
                    answered ? new LlmCallEvent.ResponseSummary(text.isEmpty() ? null : text.toString(), calls) : null,
                    errorStatus, errorBody);
        }
    }

    /** A tool call whose id, name and arguments arrive in pieces. */
    private static final class PendingToolCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void add(String idPart, String namePart, String argumentsPart) {
            if (idPart != null) id = idPart;
            if (namePart != null) name = namePart;
            if (argumentsPart != null) arguments.append(argumentsPart);
        }

        ToolCall toToolCall() {
            if (arguments.isEmpty()) {
                return new ToolCall(id, name, Map.of());
            }
            try {
                return new ToolCall(id, name, mapper().readValue(arguments.toString(), ARGUMENTS));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("The model sent arguments that are no JSON object: " + arguments, e);
            }
        }
    }
}
