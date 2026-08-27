package ai.mindconnect.agent.service.approval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of OPEN sub-agent approval questions — ONE truth per card:
 * an entry exists exactly while a bubbled request awaits its human answer.
 * Replaces the old copy mechanism (request/response messages mirrored into
 * the root conversation), which left two truths that could drift apart and
 * produced stale cards.
 *
 * <p>Lifecycle: {@code SubAgentCalls} registers on bubbling (idempotent by
 * callId — a woken-without-answer task registers nothing twice), the root
 * chat renders its cards from {@link #openForRoot}, the routed answer
 * {@link #delete}s. Cleanup beyond the happy path: cancel of the root chat,
 * the next user turn on the root session, and session deletion.
 *
 * <p>In-memory ON PURPOSE: entries point at live task ids, and the queue's
 * tasks are in-memory too — an entry that outlived a restart would be a
 * stale card pointing at a task that no longer exists, the very thing this
 * store abolishes. Thread-safe.
 */
public final class ToolApprovalStore {

    private final Map<String, ToolApproval> byCallId = new ConcurrentHashMap<>();

    /**
     * Registers an open question — idempotent by callId, so re-bubbling
     * after a wake-without-answer is a no-op.
     *
     * @return true when the entry is NEW (the caller pushes the live card
     *         only then — the UI already shows it otherwise)
     */
    public boolean saveIfAbsent(ToolApproval approval) {
        return byCallId.putIfAbsent(approval.callId(), approval) == null;
    }

    public Optional<ToolApproval> find(String callId) {
        return Optional.ofNullable(byCallId.get(callId));
    }

    /** The cards to show in {@code rootSessionId}'s chat, oldest first. */
    public List<ToolApproval> openForRoot(UUID rootSessionId) {
        return byCallId.values().stream()
                .filter(a -> rootSessionId.equals(a.rootSessionId()))
                .sorted(Comparator.comparing(ToolApproval::requestedAt))
                .toList();
    }

    /** The question is answered (or dead) — the card disappears everywhere. */
    public void delete(String callId) {
        byCallId.remove(callId);
    }

    /** Every open question of one chat — cancel and new-turn cleanup. */
    public void deleteForRoot(UUID rootSessionId) {
        byCallId.values().removeIf(a -> rootSessionId.equals(a.rootSessionId()));
    }

    /** Session deleted: drop entries it anchors on either end. */
    public void deleteForSession(UUID sessionId) {
        byCallId.values().removeIf(a -> sessionId.equals(a.rootSessionId())
                || sessionId.equals(a.originSessionId()));
    }
}
