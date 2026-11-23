package ai.mindconnect.agent.runtime.engine.adapter.llm;

import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.llm.domain.LlmCallEvent;

/**
 * Hears every provider call as the LLM gateway reports it — the request as
 * it went out, the events that came back, what it cost — together with the
 * turn it was made for. Here and not in the runtime's API, because the
 * shape of that report is the gateway's and the provider's.
 *
 * <p>Called on the gateway's thread, once per provider call, so more than
 * once for a model call the gateway retried. Whoever stores the report
 * should not do so on that thread.
 */
@FunctionalInterface
public interface LlmCallTraceListener {

    /** For a runtime that keeps no such traces. */
    LlmCallTraceListener NONE = (turnId, event) -> { };

    void onCall(TurnId turnId, LlmCallEvent event);
}
