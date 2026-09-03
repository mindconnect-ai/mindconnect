package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.agent.domain.view.AgentSessionHeader;
import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PgAgentSessionRepositoryTest {

    private static final Namespace NS = new Namespace("default");
    private static final UUID AGENT = UUID.randomUUID();

    private PgAgentSessionRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgAgentSessionRepository(TestDb.fresh("mc_agent_session")).initSchema();
    }

    private static AgentSession session(String user, String startedAt, UUID parent) {
        return new AgentSession(UUID.randomUUID(), AGENT, NS, user, UUID.randomUUID(), "t",
                SessionStatus.ACTIVE, startedAt == null ? null : Instant.parse(startedAt), null, parent, null, null);
    }

    @Test
    void aSessionSurvivesTheRoundTripWithItsCollections() {
        AgentSession s = session("david", "2026-09-03T10:00:00Z", null)
                .withActivatedTools(List.of("web_search"))
                .withApprovedTool("bash");
        repo.save(s);
        assertThat(repo.findById(s.id())).contains(s);
    }

    @Test
    void findByUserListsTopLevelSessionsNewestFirstWithNullStartsLast() {
        AgentSession oldest = session("david", "2026-09-01T00:00:00Z", null);
        AgentSession newest = session("david", "2026-09-03T00:00:00Z", null);
        AgentSession undated = session("david", null, null);
        AgentSession child = session("david", "2026-09-04T00:00:00Z", newest.id());
        AgentSession someoneElse = session("eve", "2026-09-05T00:00:00Z", null);
        for (AgentSession s : List.of(oldest, child, undated, newest, someoneElse)) repo.save(s);

        assertThat(repo.findByUser(NS, "david")).containsExactly(newest, oldest, undated);
        assertThat(repo.findByAgentDefinitionId(AGENT, NS, "david")).containsExactly(child, newest, oldest, undated);
        assertThat(repo.findByAgentDefinitionId(UUID.randomUUID(), NS, "david")).isEmpty();
        assertThat(repo.findByParentSessionId(newest.id())).containsExactly(child);
    }

    @Test
    void headersMatchTheFullSessionsFieldForField() {
        AgentSession a = session("david", "2026-09-03T00:00:00Z", null).withApprovedTool("bash");
        AgentSession b = session("david", "2026-09-01T00:00:00Z", null);
        repo.save(a);
        repo.save(b);
        repo.save(session("david", "2026-09-02T00:00:00Z", a.id()));

        var headers = repo.findHeadersByUser(NS, "david");
        assertThat(headers).hasSize(2);
        assertThat(headers).extracting(AgentSessionHeader::id).containsExactly(a.id(), b.id());
        var h = headers.get(0);
        assertThat(h).isInstanceOf(PgAgentSessionRepository.Header.class);
        assertThat(List.of(h.agentDefinitionId(), h.namespace(), h.userId(), h.conversationId(), h.title(),
                h.status(), h.startedAt()))
                .containsExactly(a.agentDefinitionId(), a.namespace(), a.userId(), a.conversationId(), a.title(),
                        a.status(), a.startedAt());
        assertThat(h.completedAt()).isNull();
        assertThat(h.parentSessionId()).isNull();
    }

    @Test
    void deleteRemovesTheSessionOnly() {
        AgentSession s = session("david", "2026-09-03T10:00:00Z", null);
        repo.save(s);
        repo.deleteById(s.id());
        assertThat(repo.findById(s.id())).isEmpty();
        repo.deleteById(s.id());
    }
}
