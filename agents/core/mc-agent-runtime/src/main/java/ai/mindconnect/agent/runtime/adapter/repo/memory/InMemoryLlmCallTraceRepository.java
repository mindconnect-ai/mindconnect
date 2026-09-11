package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.runtime.domain.TraceId;

import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link LlmCallTraceRepository} — process-lifetime storage, no persistence or retention. */
public class InMemoryLlmCallTraceRepository implements LlmCallTraceRepository {

    private final Map<TraceId, LlmCallTrace> store = new ConcurrentHashMap<>();

    @Override
    public void save(LlmCallTrace trace) {
        store.put(trace.id(), trace);
    }

    @Override
    public List<LlmCallTrace> findByTurn(ChatTurnId turnId) {
        return store.values().stream()
                .filter(t -> Objects.equals(t.context().turnId(), turnId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findBySession(SessionId sessionId) {
        return store.values().stream()
                .filter(t -> Objects.equals(t.context().sessionId(), sessionId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findByConversation(ConversationId conversationId) {
        return store.values().stream()
                .filter(t -> Objects.equals(t.context().conversationId(), conversationId))
                .toList();
    }

    @Override
    public List<LlmCallTrace> findDescendants(ChatTurnId rootTurnId) {
        // Grow the set of turns reachable from rootTurnId by following parentTurnId links.
        Set<ChatTurnId> turnsInTree = new HashSet<>();
        turnsInTree.add(rootTurnId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (LlmCallTrace t : store.values()) {
                ChatTurnId parent = t.context().parentTurnId();
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
    public Optional<LlmCallTrace> findById(TraceId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        store.values().removeIf(t -> Objects.equals(t.context().sessionId(), sessionId));
    }
}
