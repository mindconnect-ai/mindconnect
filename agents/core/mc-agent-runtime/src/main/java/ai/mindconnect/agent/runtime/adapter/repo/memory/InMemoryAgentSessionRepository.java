package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/** In-memory {@link AgentSessionRepository} — process-lifetime storage, no persistence. */
public class InMemoryAgentSessionRepository implements AgentSessionRepository {

    private final Map<SessionId, AgentSession> store = new ConcurrentHashMap<>();

    /**
     * Newest first, and tolerant of a session without a start time: one
     * unreadable timestamp should misplace a single row, not throw and take
     * the user's whole session list with it.
     */
    private static final java.util.Comparator<AgentSession> NEWEST_FIRST =
            java.util.Comparator.comparing(AgentSession::startedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));

    @Override
    public AgentSession create(AgentSession session) {
        if (store.putIfAbsent(session.id(), session) != null) {
            throw new IllegalStateException("Session " + session.id().value() + " exists already");
        }
        return session;
    }

    /** {@code computeIfPresent} runs the change while holding the entry, so updates of one session take turns. */
    @Override
    public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
        return Optional.ofNullable(store.computeIfPresent(id, (key, current) ->
                Objects.requireNonNull(change.apply(current), "update: the change returned null")));
    }

    @Override
    public Optional<AgentSession> findById(SessionId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<AgentSession> findByAgent(AgentId agent, UserId user) {
        return store.values().stream()
                .filter(s -> Objects.equals(s.agentDefinitionId(), agent)
                        && Objects.equals(s.userId(), user))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<AgentSession> findByUser(UserId userId) {
        return store.values().stream()
                .filter(s -> Objects.equals(s.userId(), userId)
                        && s.parentSessionId() == null)
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<AgentSession> findByParentSession(SessionId parent) {
        return store.values().stream()
                .filter(s -> Objects.equals(s.parentSessionId(), parent))
                .sorted(java.util.Comparator.comparing(AgentSession::startedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public void deleteById(SessionId id) {
        store.remove(id);
    }
}
