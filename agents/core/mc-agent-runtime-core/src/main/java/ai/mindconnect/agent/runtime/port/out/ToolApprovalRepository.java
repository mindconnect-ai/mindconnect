package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.ToolApproval;

import java.util.List;
import java.util.Optional;

/**
 * Storage of OPEN approval questions — ONE truth per card: an entry exists
 * exactly while a parked tool call awaits its human answer, whether the root
 * agent made the call or a sub-agent below it.
 *
 * <p>Lifecycle: the approval gate in {@code ToolCallWorker} saves when it
 * parks a call (idempotent per chat and callId — a woken-without-answer task
 * saves nothing twice), the root chat renders its cards from
 * {@link #openForRoot}, the answer {@link #delete}s. Cleanup beyond the happy
 * path: cancel of the root chat, a new message that supersedes the waiting
 * turn, and session deletion.
 *
 * <p>Keyed by the chat that shows the card <em>and</em> the call id. The call
 * id comes from the model provider and is unique only within one response, so
 * it never addresses a question on its own: an answer names the root session
 * it was given in, and a call id from another chat finds nothing.
 *
 * <p>An entry points at a live tool task ({@link ToolApproval#toolTaskId()}):
 * a repository should live as long as the task store does, or a parked task
 * outlives the question that would wake it. Implementations must be thread-safe.
 */
public interface ToolApprovalRepository {

    /**
     * Saves an open question — idempotent per chat and callId, so re-bubbling
     * after a wake-without-answer is a no-op.
     *
     * @return true when the entry is NEW (the caller pushes the live card
     *         only then — the UI already shows it otherwise)
     */
    boolean saveIfAbsent(ToolApproval approval);

    /** The open question {@code callId} shown in {@code rootSessionId}'s chat. */
    Optional<ToolApproval> find(SessionId rootSessionId, String callId);

    /** The cards to show in {@code rootSessionId}'s chat, oldest first. */
    List<ToolApproval> openForRoot(SessionId rootSessionId);

    /** The question is answered (or dead) — the card disappears everywhere. */
    void delete(SessionId rootSessionId, String callId);

    /** Every open question of one chat — cancel and new-turn cleanup. */
    void deleteForRoot(SessionId rootSessionId);

    /** Session deleted: drop entries it anchors on either end. */
    void deleteForSession(SessionId sessionId);
}
