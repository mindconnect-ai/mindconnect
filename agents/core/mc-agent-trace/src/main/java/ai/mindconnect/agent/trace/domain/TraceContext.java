package ai.mindconnect.agent.trace.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import ai.mindconnect.agent.SessionId;

/**
 * Identifies the agent-runtime context that issued an LLM call: which
 * session, turn, and (for sub-agents) which parent turn.
 *
 * <p>Threaded through to the {@link ai.mindconnect.llm.runtime.api.port.out.LlmCallListener}
 * the runtime attaches per call. The gateway itself knows nothing about this
 * — it only sees the raw {@link ai.mindconnect.llm.runtime.api.domain.LlmCallEvent},
 * which the runtime then combines with this context into a persisted
 * {@link LlmCallTrace}.
 *
 * <p>A trace written before also named the conversation the session was kept
 * in; that is read past.
 *
 * @param turnId        the turn that made the call
 * @param parentTurnId  null for top-level turns; set for sub-agent turns,
 *                      pointing at the parent turn that spawned this one
 * @param depth         0 for top-level, 1+ for sub-agents
 * @param agentName     display name of the agent issuing the call, for UI
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraceContext(
        SessionId sessionId,
        String turnId,
        String parentTurnId,
        int depth,
        String agentName
) {

}
