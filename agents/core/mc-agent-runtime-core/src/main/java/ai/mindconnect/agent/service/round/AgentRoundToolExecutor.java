package ai.mindconnect.agent.service.round;

import java.util.UUID;

/**
 * Executes tool calls — asynchronously. {@link #execute} only starts and
 * returns immediately, {@link #result} collects when it is done. That is what
 * lets one round dispatch several calls at once instead of awaiting them one
 * after another — and what lets a queue-backed implementation run them as
 * child tasks.
 *
 * <p>Progress reporting is the implementation's business: it knows requestId
 * and callId and can publish on the request's channel. The round hands
 * nothing through — a sink it passed along would belong to a round that is
 * long over when the tool finishes.
 */
public interface AgentRoundToolExecutor {

    /**
     * Starts the execution and returns immediately.
     *
     * <p><b>Must be idempotent per callId.</b> The round records a
     * TOOL_DISPATCHED marker, but marker and start cannot be atomic across a
     * crash — only idempotency here keeps a non-repeatable tool from running
     * twice.
     */
    void execute(String requestId, UUID sessionId, ToolCalls.Call call);

    /** The state of a started execution. */
    ToolResult result(UUID sessionId, String callId);
}
