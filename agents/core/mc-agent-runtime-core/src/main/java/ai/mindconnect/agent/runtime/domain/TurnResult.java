package ai.mindconnect.agent.runtime.domain;

import ai.mindconnect.message.domain.ChatTurnId;

import java.util.List;

/**
 * What a turn has delivered to one handle: its final answer, or the questions it waits on.
 *
 * <p>{@link TurnStatus#INCOMPLETE} does not end the turn. The tool call waits at the approval
 * gate, and answering one of {@link #pendingApprovals} continues the same turn — the answer
 * hands back a new handle that runs on until the turn completes or asks again.
 *
 * @param turnId           the turn this result belongs to
 * @param status           {@link TurnStatus#COMPLETED} or {@link TurnStatus#INCOMPLETE}
 * @param text             the final answer; {@code null} while incomplete
 * @param pendingApprovals the open questions, oldest first; empty when completed
 */
public record TurnResult(ChatTurnId turnId, TurnStatus status, String text,
                         List<ToolApproval> pendingApprovals) {

    public TurnResult {
        pendingApprovals = pendingApprovals == null ? List.of() : List.copyOf(pendingApprovals);
    }

    public static TurnResult completed(ChatTurnId turnId, String text) {
        return new TurnResult(turnId, TurnStatus.COMPLETED, text, List.of());
    }

    public static TurnResult incomplete(ChatTurnId turnId, List<ToolApproval> pendingApprovals) {
        return new TurnResult(turnId, TurnStatus.INCOMPLETE, null, pendingApprovals);
    }

    public boolean isIncomplete() {
        return status == TurnStatus.INCOMPLETE;
    }
}
