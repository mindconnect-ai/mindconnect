package ai.mindconnect.agent.domain;

import java.util.UUID;

/**
 * Identifies the agent-runtime context that issued an LLM call: which
 * conversation, session, turn, and (for sub-agents) which parent turn.
 *
 * <p>Threaded through to the {@link ai.mindconnect.llm.port.in.LlmCallListener}
 * the {@code ToolLoopRunner} attaches per call. The gateway itself knows
 * nothing about this — it only sees the raw {@link
 * ai.mindconnect.llm.domain.LlmCallEvent}, which the runtime then combines
 * with this context into a persisted {@link LlmCallTrace}.
 *
 * @param parentTurnId  null for top-level turns; set for sub-agent turns,
 *                      pointing at the parent turn that spawned this one
 * @param depth         0 for top-level, 1+ for sub-agents
 * @param agentName     display name of the agent issuing the call, for UI
 */
public record TraceContext(
        UUID conversationId,
        UUID sessionId,
        UUID turnId,
        UUID parentTurnId,
        int depth,
        String agentName
) {}
