package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmCallTraceRepository} — process-lifetime storage, no persistence or retention. */
public class InMemoryLlmCallTraceRepository implements LlmCallTraceRepository {

    private final Map<UUID, LlmCallTrace> store = new ConcurrentHashMap<>();

    @Override
    public void save(LlmCallTrace trace) {
        store.put(trace.id(), trace);
    }

    @Override
    public List<LlmCallTrace> findByTurn(UUID turnId) {
        return store.values().stream()
                .filter(t -> Objects.equals(t.context().turnId(), turnId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findBySession(UUID sessionId) {
        return store.values().stream()
                .filter(t -> Objects.equals(t.context().sessionId(), sessionId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findByConversation(UUID conversationId) {
        return store.values().stream()
                .filter(t -> Objects.equals(t.context().conversationId(), conversationId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findDescendants(UUID rootTurnId) {
        // Grow the set of turns reachable from rootTurnId by following parentTurnId links.
        Set<UUID> turnsInTree = new HashSet<>();
        turnsInTree.add(rootTurnId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (LlmCallTrace t : store.values()) {
                UUID parent = t.context().parentTurnId();
                if (parent != null && turnsInTree.contains(parent) && turnsInTree.add(t.context().turnId())) {
                    changed = true;
                }
            }
        }
        return store.values().stream()
                .filter(t -> turnsInTree.contains(t.context().turnId()))
                .toList();
    }

    @Override
    public Optional<LlmCallTrace> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void deleteBySession(UUID sessionId) {
        store.values().removeIf(t -> Objects.equals(t.context().sessionId(), sessionId));
    }
}
