package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link AgentSessionRepository} — process-lifetime storage, no persistence. */
public class InMemoryAgentSessionRepository implements AgentSessionRepository {

    private final Map<UUID, AgentSession> store = new ConcurrentHashMap<>();

    @Override
    public AgentSession save(AgentSession session) {
        store.put(session.id(), session);
        return session;
    }

    @Override
    public Optional<AgentSession> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<AgentSession> findByAgentDefinitionId(UUID agentDefinitionId, Namespace namespace, String userId) {
        return store.values().stream()
                .filter(s -> Objects.equals(s.agentDefinitionId(), agentDefinitionId)
                        && Objects.equals(s.namespace(), namespace)
                        && Objects.equals(s.userId(), userId))
                .toList();
    }

    @Override
    public List<AgentSession> findByUser(Namespace namespace, String userId) {
        return store.values().stream()
                .filter(s -> Objects.equals(s.namespace(), namespace)
                        && Objects.equals(s.userId(), userId)
                        && s.parentSessionId() == null)
                .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                .toList();
    }

    @Override
    public List<AgentSession> findByParentSessionId(UUID parentSessionId) {
        return store.values().stream()
                .filter(s -> Objects.equals(s.parentSessionId(), parentSessionId))
                .toList();
    }

    @Override
    public void deleteById(UUID id) {
        store.remove(id);
    }
}
