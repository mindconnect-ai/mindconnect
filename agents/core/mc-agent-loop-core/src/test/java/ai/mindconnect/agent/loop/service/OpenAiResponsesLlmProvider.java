package ai.mindconnect.agent.loop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.LlmProvider;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;

/**
 * {@link LlmProvider} over the real OpenAI Responses API, as small as a test
 * needs it: one blocking call per round and therefore no deltas, nothing stored at OpenAI — the
 * loop's history is the only memory, rendered into input items every time.
 *
 * <p>The items of a tool-call turn ride back verbatim in
 * {@code metadata["openai.items"]}: a reasoning model wants its reasoning
 * item replayed in front of the function call it led to, and only this
 * adapter knows that shape.
 */
final class OpenAiResponsesLlmProvider implements LlmProvider {

    private static final String REPLAY_ITEMS = "openai.items";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final String model;
    private final String instructions;
    private final List<Map<String, Object>> tools;
    private final Map<String, CompletableFuture<HttpResponse<String>>> inFlight = new ConcurrentHashMap<>();
    private final Set<String> aborted = ConcurrentHashMap.newKeySet();

    /** @param tools function tools in the Responses wire shape ({@code type: function, name, parameters}) */
    OpenAiResponsesLlmProvider(String apiKey, String model, String instructions, List<Map<String, Object>> tools) {
        this.apiKey = apiKey;
        this.model = model;
        this.instructions = instructions;
        this.tools = tools;
    }

    @Override
    public LlmAnswer ask(String requestId, String sessionId, List<TurnMessage> history,
                         TurnEventListener events) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("instructions", instructions);
        body.put("input", inputItems(history));
        body.put("tools", tools);
        body.put("store", false);
        // Without a stored response the reasoning can only be replayed from its encrypted form.
        body.put("include", List.of("reasoning.encrypted_content"));
        try {
            return answerOf(post(requestId, body));
        } catch (CancellationException e) {
            aborted.remove(requestId);
            // Aborted before anything arrived: a blocking call has no partial answer.
            return new LlmAnswer(List.of(), Usage.ZERO, false);
        }
    }

    @Override
    public void cancel(String requestId) {
        CompletableFuture<HttpResponse<String>> call = inFlight.get(requestId);
        if (call != null) {
            aborted.add(requestId);
            call.cancel(true);
        }
    }

    // ── history → input items ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inputItems(List<TurnMessage> history) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (TurnMessage message : history) {
            switch (message.type()) {
                case USER -> items.add(Map.of("role", "user", "content", message.text()));
                case ASSISTANT -> items.add(Map.of("role", "assistant", "content", message.text()));
                case TOOL_CALL -> items.addAll((List<Map<String, Object>>) message.metadata().get(REPLAY_ITEMS));
                case TOOL_RESULT -> items.add(Map.of(
                        "type", "function_call_output",
                        "call_id", message.metadata().get("callId"),
                        "output", message.text()));
                case TOOL_DISPATCHED -> { }
            }
        }
        return items;
    }

    // ── response → answer ───────────────────────────────────────────────────

    private LlmAnswer answerOf(JsonNode response) {
        StringBuilder text = new StringBuilder();
        List<TurnMessage.ToolCall> calls = new ArrayList<>();
        List<Map<String, Object>> replay = new ArrayList<>();
        for (JsonNode item : response.path("output")) {
            switch (item.path("type").asText()) {
                case "message" -> item.path("content").forEach(part -> text.append(part.path("text").asText("")));
                case "function_call" -> {
                    calls.add(new TurnMessage.ToolCall(item.path("call_id").asText(), item.path("name").asText(),
                            arguments(item.path("arguments").asText("{}"))));
                    replay.add(json.convertValue(item, MAP));
                }
                case "reasoning" -> replay.add(json.convertValue(item, MAP));
                default -> { }
            }
        }
        Usage usage = new Usage(response.path("usage").path("input_tokens").asLong(),
                response.path("usage").path("output_tokens").asLong());
        boolean truncated = "max_output_tokens".equals(response.path("incomplete_details").path("reason").asText());
        TurnMessage message = calls.isEmpty()
                ? TurnMessage.assistant(text.toString())
                : TurnMessage.toolCalls(calls).with(REPLAY_ITEMS, replay);
        return new LlmAnswer(List.of(message), usage, truncated);
    }

    private Map<String, Object> arguments(String argumentsJson) {
        try {
            return json.readValue(argumentsJson, MAP);
        } catch (IOException e) {
            throw new IllegalStateException("OpenAI sent tool arguments that are not a JSON object: " + argumentsJson, e);
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private JsonNode post(String requestId, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            CompletableFuture<HttpResponse<String>> call =
                    http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            inFlight.put(requestId, call);
            HttpResponse<String> response = call.get();
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("OpenAI answered " + response.statusCode() + ": " + response.body());
            }
            return json.readTree(response.body());
        } catch (ExecutionException e) {
            // The JDK client reports an aborted exchange as a failed one.
            if (aborted.remove(requestId)) throw new CancellationException("OpenAI call aborted");
            throw new IllegalStateException("OpenAI call failed", e);
        } catch (IOException e) {
            throw new IllegalStateException("OpenAI call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI call interrupted", e);
        } finally {
            inFlight.remove(requestId);
        }
    }
}
