package ai.mindconnect.agent.chat.stream;

import java.util.Map;

/**
 * One frame of a turn as a client reads it on the wire: a type, and of the
 * other fields those that type uses. It is made from what the runtime
 * reports and has no other form in between — what a client sees is what
 * {@link TurnFrameTranslator} made of an event.
 *
 * <p>The field names are the contract with clients; a frame that needs
 * something new reuses a field before the record grows.
 */
public record TurnFrame(String type, String text, String toolName,
                        Map<String, Object> arguments, String result,
                        Long durationMs,
                        String reviewerName, String verdict,
                        String finalText, String reason, Boolean blocked,
                        String taskId, String agentName, Integer depth,
                        String error, String subSessionId,
                        TurnFrame inner,
                        Long inputTokens, Long outputTokens) {

    /** The model is asked. */
    public static TurnFrame askingLlm() {
        return of("asking_llm", null);
    }

    public static TurnFrame token(String text) {
        return of("token", text);
    }

    public static TurnFrame thinking(String text) {
        return of("thinking", text);
    }

    public static TurnFrame toolCallStarted(String toolName, Map<String, Object> arguments) {
        return new TurnFrame("tool_call_started", null, toolName, arguments, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public static TurnFrame toolCallResult(String toolName, String result, long durationMs) {
        return new TurnFrame("tool_call_result", null, toolName, null, result, durationMs, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public static TurnFrame toolCallFailed(String toolName, String error, long durationMs) {
        return new TurnFrame("tool_call_failed", null, toolName, null, null, durationMs, null, null, null, null,
                null, null, null, null, error, null, null, null, null);
    }

    public static TurnFrame turnUsage(long inputTokens, long outputTokens) {
        return new TurnFrame("turn_usage", null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, inputTokens, outputTokens);
    }

    /** The turn came to its end. */
    public static TurnFrame done() {
        return of("done", null);
    }

    /** The turn broke off; {@code text} says why. */
    public static TurnFrame error(String message) {
        return of("error", message);
    }

    /**
     * A tool call waits for an approval. The ids ride the generic fields so
     * the record needs no new ones: {@code text} is the call's id,
     * {@code taskId} and {@code error} the request's, {@code subSessionId}
     * the session the call was made in.
     */
    public static TurnFrame approvalRequested(String requestId, String callId, String toolName,
                                              Map<String, Object> arguments, String originSessionId) {
        return new TurnFrame("approval_requested", callId, toolName, arguments, null, null, null, null, null, null,
                null, requestId, null, null, requestId, originSessionId, null, null, null);
    }

    public static TurnFrame subAgentStarted(String taskId, String agentName, int depth, String subSessionId) {
        return new TurnFrame("sub_agent_started", "", null, null, null, null, null, null, null, null, null, taskId,
                agentName, depth, null, subSessionId, null, null, null);
    }

    /** What a sub-agent's turn reports, under the task it runs as. */
    public static TurnFrame subAgentEvent(String taskId, TurnFrame inner) {
        return new TurnFrame("sub_agent_event", null, null, null, null, null, null, null, null, null, null, taskId,
                null, null, null, null, inner, null, null);
    }

    public static TurnFrame subAgentDone(String taskId, String agentName, String subSessionId, String finalText) {
        return new TurnFrame("sub_agent_done", null, null, null, null, null, null, null, finalText, null, null,
                taskId, agentName, null, null, subSessionId, null, null, null);
    }

    private static TurnFrame of(String type, String text) {
        return new TurnFrame(type, text, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
