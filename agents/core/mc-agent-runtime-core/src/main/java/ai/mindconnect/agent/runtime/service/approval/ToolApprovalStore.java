package ai.mindconnect.agent.runtime.service.approval;

import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.SessionId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of OPEN approval questions — ONE truth per card: an entry
 * exists exactly while a parked tool call awaits its human answer, whether
 * the root agent made the call or a sub-agent below it.
 *
 * <p>Lifecycle: the approval gate in {@code ToolCallWorker} registers when it
 * parks a call (idempotent per chat and callId — a woken-without-answer task
 * registers nothing twice), the root chat renders its cards from
 * {@link #openForRoot}, the answer {@link #delete}s. Cleanup beyond the happy
 * path: cancel of the root chat, a new message that supersedes the waiting
 * turn, and session deletion.
 *
 * <p>Keyed by the chat that shows the card <em>and</em> the call id. The call
 * id comes from the model provider and is unique only within one response, so
 * it never addresses a question on its own: an answer names the root session
 * it was given in, and a call id from another chat finds nothing.
 *
 * <p>In-memory: entries point at live task ids. With a persistent task store
 * a parked task outlives a restart while its entry does not — the question is
 * then unanswerable until the store persists too (concept 33, step 4).
 * Thread-safe.
 */
public final class ToolApprovalStore {

    private record Key(SessionId rootSessionId, String callId) { }

    private final Map<Key, ToolApproval> open = new ConcurrentHashMap<>();

    /**
     * Registers an open question — idempotent per chat and callId, so
     * re-bubbling after a wake-without-answer is a no-op.
     *
     * @return true when the entry is NEW (the caller pushes the live card
     *         only then — the UI already shows it otherwise)
     */
    public boolean saveIfAbsent(ToolApproval approval) {
        return open.putIfAbsent(keyOf(approval), approval) == null;
    }

    /** The open question {@code callId} shown in {@code rootSessionId}'s chat. */
    public Optional<ToolApproval> find(SessionId rootSessionId, String callId) {
        return Optional.ofNullable(open.get(new Key(rootSessionId, callId)));
    }

    /** The cards to show in {@code rootSessionId}'s chat, oldest first. */
    public List<ToolApproval> openForRoot(SessionId rootSessionId) {
        return open.values().stream()
                .filter(a -> rootSessionId.equals(a.rootSessionId()))
                .sorted(Comparator.comparing(ToolApproval::requestedAt))
                .toList();
    }

    /** The question is answered (or dead) — the card disappears everywhere. */
    public void delete(SessionId rootSessionId, String callId) {
        open.remove(new Key(rootSessionId, callId));
    }

    /** Every open question of one chat — cancel and new-turn cleanup. */
    public void deleteForRoot(SessionId rootSessionId) {
        open.values().removeIf(a -> rootSessionId.equals(a.rootSessionId()));
    }

    /** Session deleted: drop entries it anchors on either end. */
    public void deleteForSession(SessionId sessionId) {
        open.values().removeIf(a -> sessionId.equals(a.rootSessionId())
                || sessionId.equals(a.originSessionId()));
    }

    private static Key keyOf(ToolApproval approval) {
        return new Key(approval.rootSessionId(), approval.callId());
    }
}
