package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ToolApprovalRepository} in memory — the default. Nothing survives a
 * restart: with a persistent task store a parked task outlives its entry and
 * cannot be answered any more, so such an installation needs a repository that
 * persists as well.
 */
public class InMemoryToolApprovalRepository implements ToolApprovalRepository {

    private record Key(SessionId rootSessionId, String callId) { }

    private final Map<Key, ToolApproval> open = new ConcurrentHashMap<>();

    /**
     * Registers an open question — idempotent per chat and callId, so
     * re-bubbling after a wake-without-answer is a no-op.
     *
     * @return true when the entry is NEW (the caller pushes the live card
     *         only then — the UI already shows it otherwise)
     */
    @Override
    public boolean saveIfAbsent(ToolApproval approval) {
        return open.putIfAbsent(keyOf(approval), approval) == null;
    }

    /** The open question {@code callId} shown in {@code rootSessionId}'s chat. */
    @Override
    public Optional<ToolApproval> find(SessionId rootSessionId, String callId) {
        return Optional.ofNullable(open.get(new Key(rootSessionId, callId)));
    }

    /** The cards to show in {@code rootSessionId}'s chat, oldest first. */
    @Override
    public List<ToolApproval> openForRoot(SessionId rootSessionId) {
        return open.values().stream()
                .filter(a -> rootSessionId.equals(a.rootSessionId()))
                .sorted(Comparator.comparing(ToolApproval::requestedAt))
                .toList();
    }

    /** The question is answered (or dead) — the card disappears everywhere. */
    @Override
    public void delete(SessionId rootSessionId, String callId) {
        open.remove(new Key(rootSessionId, callId));
    }

    /** Every open question of one chat — cancel and new-turn cleanup. */
    @Override
    public void deleteForRoot(SessionId rootSessionId) {
        open.values().removeIf(a -> rootSessionId.equals(a.rootSessionId()));
    }

    /** Session deleted: drop entries it anchors on either end. */
    @Override
    public void deleteForSession(SessionId sessionId) {
        open.values().removeIf(a -> sessionId.equals(a.rootSessionId())
                || sessionId.equals(a.originSessionId()));
    }

    private static Key keyOf(ToolApproval approval) {
        return new Key(approval.rootSessionId(), approval.callId());
    }
}
