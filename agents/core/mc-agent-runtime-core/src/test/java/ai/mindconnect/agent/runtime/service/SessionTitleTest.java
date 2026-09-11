package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The title generator runs after the first exchange, and the user may rename
 * the chat in the meantime. The generated title names an untitled chat only.
 */
class SessionTitleTest {

    private final Map<SessionId, AgentSession> stored = new ConcurrentHashMap<>();
    private final AgentSessionService service =
            new AgentSessionService(null, new MapSessions(stored), null, null, null, null, null, null);

    @Test
    void theGeneratedTitleNamesAnUntitledChat() {
        AgentSession session = store(newSession());

        AgentSession titled = service.titleIfUntitled(session.id(), "Generated");

        assertThat(titled.title()).isEqualTo("Generated");
        assertThat(stored.get(session.id()).title()).isEqualTo("Generated");
    }

    @Test
    void theGeneratedTitleDoesNotOverwriteANameTheUserGave() {
        AgentSession session = store(newSession());
        service.updateTitle(session.id(), "Weekly report");

        AgentSession titled = service.titleIfUntitled(session.id(), "Generated");

        assertThat(titled.title()).isEqualTo("Weekly report");
        assertThat(stored.get(session.id()).title()).isEqualTo("Weekly report");
    }

    @Test
    void renamingAMissingSessionIsNotFound() {
        assertThatThrownBy(() -> service.updateTitle(SessionId.random(), "x"))
                .isInstanceOf(DomainException.class);
    }

    private AgentSession store(AgentSession session) {
        stored.put(session.id(), session);
        return session;
    }

    private static AgentSession newSession() {
        return AgentSession.start(AgentId.random(), UserId.of("alice"), ConversationId.random());
    }

    private record MapSessions(Map<SessionId, AgentSession> byId) implements AgentSessionRepository {
        @Override public AgentSession create(AgentSession s) {
            if (byId.putIfAbsent(s.id(), s) != null) throw new IllegalStateException("exists");
            return s;
        }
        @Override public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
            return Optional.ofNullable(byId.computeIfPresent(id, (key, current) -> change.apply(current)));
        }
        @Override public Optional<AgentSession> findById(SessionId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<AgentSession> findByAgent(AgentId agent, UserId user) { return List.of(); }
        @Override public List<AgentSession> findByUser(UserId user) { return List.of(); }
        @Override public List<AgentSession> findByParentSession(SessionId parent) { return List.of(); }
        @Override public void deleteById(SessionId id) { byId.remove(id); }
    }
}
