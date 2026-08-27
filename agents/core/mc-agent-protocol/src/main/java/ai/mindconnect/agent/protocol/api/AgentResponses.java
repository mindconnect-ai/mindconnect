package ai.mindconnect.agent.protocol.api;

import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.event.ResponseEvent;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The response side of the protocol — the ONLY inbound command surface
 * (concept 4: observation is a broadcast, command is a queue). Every
 * transport is a thin adapter onto these four methods:
 *
 * <pre>
 * in-memory   direct calls on this interface (embedded builder)
 * HTTP        POST /responses            → create
 *             GET  /responses/{id}       → get
 *             POST /responses/{id}/cancel → cancel
 * SSE         POST /responses?stream=true, GET /responses/{id}/events?after_seq=n → subscribe
 * WebSocket   command frames → create/cancel (with ack), event frames ← subscribe
 * </pre>
 *
 * There are no request/response channels: commands need exactly-once
 * delivery, a result, errors and authorization — that is an API call, not
 * an event.
 */
public interface AgentResponses {

    /**
     * Creates a response on the session's conversation. Blocking unless
     * {@link ResponseRequest#background()} — then the returned snapshot is
     * the freshly queued response.
     */
    Response create(ResponseRequest request);

    /** Current snapshot, including output items produced so far. */
    Optional<Response> get(String responseId);

    /**
     * Cooperative cancel of the response and, recursively, its children.
     * A parent whose child was individually cancelled sees an error tool
     * result and can re-plan.
     *
     * @return {@code false} if the response was already terminal
     */
    boolean cancel(String responseId);

    /** Attaches a consumer to the response's event stream. Never blocks the producer. */
    Subscription subscribe(SubscribeRequest request, Consumer<ResponseEvent> consumer);
}
