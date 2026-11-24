package ai.mindconnect.agent.runtime.llm;

import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.llm.domain.LlmCallEvent;

/**
 * Hears every provider call as the LLM gateway reports it — the request as
 * it went out, the events that came back, what it cost — together with the
 * turn it was made for. Here and not in the runtime's API, because the
 * shape of that report is the gateway's and the provider's.
 *
 * <p>Called once per provider call, so more than once for a model call the
 * gateway retried, and on the thread of the turn after the model call has
 * ended: the turn's namespace is bound, and the turn can be looked up.
 */
@FunctionalInterface
public interface LlmCallTraceListener {

    /** For a runtime that keeps no such traces. */
    LlmCallTraceListener NONE = (turnId, event) -> { };

    void onCall(TurnId turnId, LlmCallEvent event);
}
