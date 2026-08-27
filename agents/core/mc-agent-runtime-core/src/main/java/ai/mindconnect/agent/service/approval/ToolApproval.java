package ai.mindconnect.agent.service.approval;

import java.time.Instant;
import java.util.UUID;

/**
 * One OPEN approval question bubbled up from a sub-agent — everything the
 * root UI needs to show the card and everything the answer needs to route
 * back down. Lives in the {@link ToolApprovalStore} from the moment the
 * sub-turn suspends until the human answers (or the chat is cancelled,
 * restarted with a new turn, or its session deleted).
 *
 * <p>Root-level requests never become entries: their card renders straight
 * off the origin conversation's own {@code APPROVAL_REQUEST} message.
 *
 * @param requestId       the request's id, echoed into the response message
 * @param callId          the tool call awaiting the verdict — the store key
 * @param toolName        for "allow for this session" on the root session
 * @param content         the request message's JSON ({@code name}/{@code arguments}) — what the card shows
 * @param originSessionId the sub-session whose conversation holds the waiting call
 * @param rootSessionId   the session whose chat shows the card
 * @param toolTaskId      the suspended tool task whose doorbell the answer rings
 * @param requestedAt     card ordering in the UI
 */
public record ToolApproval(
        String requestId,
        String callId,
        String toolName,
        String content,
        UUID originSessionId,
        UUID rootSessionId,
        String toolTaskId,
        Instant requestedAt) {
}
