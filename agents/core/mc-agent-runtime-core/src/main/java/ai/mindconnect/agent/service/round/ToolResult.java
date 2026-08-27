package ai.mindconnect.agent.service.round;

/**
 * What became of a dispatched tool call. Execution is asynchronous:
 * {@link AgentRoundToolExecutor#execute} only starts it, the result is collected in a
 * later round.
 *
 * <p>{@link Lost} is deliberately not the same as {@link Running}: an executor
 * that no longer knows the callId after a restart would otherwise count as
 * "still running" forever and the round would never advance.
 */
public sealed interface ToolResult {

    /** Still running — nothing to do, the next round asks again. */
    record Running() implements ToolResult { }

    /**
     * Finished. {@code failed} tells the model whether it worked;
     * {@code durationMs} is the measured wall-clock time (0 when unknown).
     */
    record Finished(String output, boolean failed, long durationMs) implements ToolResult { }

    /** The executor does not know this callId (any more) — the call is lost. */
    record Lost(String reason) implements ToolResult { }

    static ToolResult ok(String output) {
        return new Finished(output, false, 0);
    }

    static ToolResult failed(String error) {
        return new Finished(error, true, 0);
    }
}
