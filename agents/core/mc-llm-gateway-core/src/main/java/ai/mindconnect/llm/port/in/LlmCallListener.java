package ai.mindconnect.llm.port.in;

import ai.mindconnect.llm.domain.LlmCallEvent;

/**
 * Callback invoked once per provider call after the adapter has finished
 * (successfully or not). Passed by the caller into {@code chatStreaming(...)}
 * — the gateway has no knowledge of who the listener is or what it does
 * with the event.
 *
 * <p>Implementations:
 * <ul>
 *   <li>are called from the gateway's worker thread — long-running work
 *       (DB writes, HTTP pushes) should be queued, not done inline;</li>
 *   <li>must <em>not</em> throw — the adapter wraps the call in a
 *       try/catch and swallows any exception (a broken trace must never
 *       break the actual chat).</li>
 * </ul>
 *
 * <p>Use {@link #NOOP} for callers that don't care about tracing.
 */
@FunctionalInterface
public interface LlmCallListener {

    /** Called exactly once per {@code chatStreaming(...)} invocation. */
    void onCall(LlmCallEvent event);

    /** Default no-op listener for callers that don't want tracing. */
    LlmCallListener NOOP = event -> {};
}
