package ai.mindconnect.agent.loop.domain;

import java.util.Map;

/**
 * The turn stops to ask somebody and stands still until it is resumed with
 * an answer to {@link #requestId}. The loop itself only sees a call that has
 * not answered yet; whoever runs the tools knows that somebody is asked, and
 * tells so with one of these.
 */
public interface TurnInterruptionEvent extends TurnStreamEvent {

    /** What an answer names, to say which question it answers. */
    String requestId();

    /** The tool call that waits; {@code null} when the question belongs to no call. */
    String callId();

    /** The tool of the call that waits; {@code null} when the question belongs to no call. */
    String toolName();

    /**
     * A tool call waits for somebody's approval.
     *
     * @param originSessionId the session the call was made in — a sub-agent's, when one asks
     */
    record ApprovalRequested(String requestId, String callId, String toolName, Map<String, Object> arguments,
                             String originSessionId) implements TurnInterruptionEvent {
        @Override public String kind() {
            return "approval_requested";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("requestId", requestId, "callId", callId, "toolName", toolName,
                    "arguments", arguments, "originSessionId", originSessionId);
        }
    }

    /**
     * The turn asks something that is no tool approval — a question to the
     * user, or what a tool of its own kind asks.
     */
    record InputRequested(String requestId, String interruptionKind, String callId, String toolName,
                          Map<String, Object> details) implements TurnInterruptionEvent {
        @Override public String kind() {
            return "input_requested";
        }

        @Override public Map<String, Object> toDetails() {
            return StreamEvent.detailsOf("requestId", requestId, "interruptionKind", interruptionKind,
                    "callId", callId, "toolName", toolName, "details", details);
        }
    }
}
