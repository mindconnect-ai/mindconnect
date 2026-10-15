package ai.mindconnect.agentrest.dto;

import ai.mindconnect.agent.runtime.domain.ToolApproval;

import java.util.List;

/**
 * The last frame of a turn stream that stopped at the approval gate: the turn
 * waits for {@code pendingApprovals}, and {@code POST
 * /api/sessions/{id}/approvals/{callId}/continue} answers one of them and
 * streams the same turn on.
 */
public record IncompleteFrame(String type, String turnId, List<ToolApproval> pendingApprovals) {

    public static IncompleteFrame of(String turnId, List<ToolApproval> pendingApprovals) {
        return new IncompleteFrame("incomplete", turnId, List.copyOf(pendingApprovals));
    }
}
