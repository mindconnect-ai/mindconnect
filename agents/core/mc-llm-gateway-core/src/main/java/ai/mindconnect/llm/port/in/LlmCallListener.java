package ai.mindconnect.llm.port.in;

import ai.mindconnect.llm.domain.LlmCallEvent;

/**
 * Called once per provider call after the adapter has finished, successfully or
 * not; the gateway knows nothing about what the listener does with the event. It
 * runs on the gateway's worker thread, so long-running work such as database writes
 * should be queued, and it must not throw: the adapter swallows the exception,
 * because a broken trace must never break the chat. {@link #NOOP} is for callers
 * that do not trace.
 */
@FunctionalInterface
public interface LlmCallListener {

    /** Called exactly once per {@code chatStreaming(...)} invocation. */
    void onCall(LlmCallEvent event);

    /** Default no-op listener for callers that don't want tracing. */
    LlmCallListener NOOP = event -> {};
}
