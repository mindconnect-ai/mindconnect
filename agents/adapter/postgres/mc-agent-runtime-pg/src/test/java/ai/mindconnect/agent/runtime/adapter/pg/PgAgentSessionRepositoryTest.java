package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgAgentSessionRepositoryTest {

    private static final Namespace NS = new Namespace("test");
    private static final AgentId AGENT = AgentId.random();

    private PgAgentSessionRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgAgentSessionRepository(TestDb.fresh("mc_agent_session"), NS).initSchema();
    }

    private static AgentSession session(String user, String startedAt, SessionId parent) {
        return new AgentSession(SessionId.random(), AGENT, UserId.of(user), ConversationId.random(), "t",
                SessionStatus.ACTIVE, startedAt == null ? null : Instant.parse(startedAt), null, parent, null, null);
    }

    @Test
    void aSessionSurvivesTheRoundTripWithItsCollections() {
        AgentSession s = session("david", "2026-09-03T10:00:00Z", null)
                .withActivatedTools(List.of("web_search"))
                .withApprovedTool("bash");
        repo.create(s);
        assertThat(repo.findById(s.id())).contains(s);
    }

    @Test
    void findByUserListsTopLevelSessionsNewestFirstWithNullStartsLast() {
        AgentSession oldest = session("david", "2026-09-01T00:00:00Z", null);
        AgentSession newest = session("david", "2026-09-03T00:00:00Z", null);
        AgentSession undated = session("david", null, null);
        AgentSession child = session("david", "2026-09-04T00:00:00Z", newest.id());
        AgentSession someoneElse = session("eve", "2026-09-05T00:00:00Z", null);
        for (AgentSession s : List.of(oldest, child, undated, newest, someoneElse)) repo.create(s);

        assertThat(repo.findByUser(UserId.of("david"))).containsExactly(newest, oldest, undated);
        assertThat(repo.findByAgent(AGENT, UserId.of("david"))).containsExactly(child, newest, oldest, undated);
        assertThat(repo.findByAgent(AgentId.random(), UserId.of("david"))).isEmpty();
        assertThat(repo.findByParentSession(newest.id())).containsExactly(child);
    }

    @Test
    void headersMatchTheFullSessionsFieldForField() {
        AgentSession a = session("david", "2026-09-03T00:00:00Z", null).withApprovedTool("bash");
        AgentSession b = session("david", "2026-09-01T00:00:00Z", null);
        repo.create(a);
        repo.create(b);
        repo.create(session("david", "2026-09-02T00:00:00Z", a.id()));

        var headers = repo.findHeadersByUser(UserId.of("david"));
        assertThat(headers).hasSize(2);
        assertThat(headers).extracting(AgentSessionHeader::id).containsExactly(a.id(), b.id());
        var h = headers.get(0);
        assertThat(h).isInstanceOf(PgAgentSessionRepository.Header.class);
        assertThat(List.of(h.agentDefinitionId(), h.userId(), h.conversationId(), h.title(),
                h.status(), h.startedAt()))
                .containsExactly(a.agentDefinitionId(), a.userId(), a.conversationId(), a.title(),
                        a.status(), a.startedAt());
        assertThat(h.completedAt()).isNull();
        assertThat(h.parentSessionId()).isNull();
    }

    @Test
    void deleteRemovesTheSessionOnly() {
        AgentSession s = session("david", "2026-09-03T10:00:00Z", null);
        repo.create(s);
        repo.deleteById(s.id());
        assertThat(repo.findById(s.id())).isEmpty();
        repo.deleteById(s.id());
    }

    @Test
    void createRefusesAnExistingSession() {
        AgentSession s = repo.create(session("david", "2026-09-03T10:00:00Z", null));

        assertThatThrownBy(() -> repo.create(s.withTitle("again"))).isInstanceOf(IllegalStateException.class);
        assertThat(repo.findById(s.id())).contains(s);
    }

    @Test
    void concurrentUpdatesOfOneSessionAllLand() throws Exception {
        AgentSession s = repo.create(session("david", "2026-09-03T10:00:00Z", null));

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String tool = "tool_" + i;
                futures.add(pool.submit(() -> repo.update(s.id(), x -> x.withActivatedTools(List.of(tool)))));
                futures.add(pool.submit(() -> repo.update(s.id(), x -> x.withApprovedTool(tool))));
            }
            for (Future<?> future : futures) future.get();
        }

        AgentSession stored = repo.findById(s.id()).orElseThrow();
        String[] all = IntStream.range(0, 40).mapToObj(i -> "tool_" + i).toArray(String[]::new);
        assertThat(stored.activatedTools()).containsExactlyInAnyOrder(all);
        assertThat(stored.approvedTools()).containsExactlyInAnyOrder(all);
    }

    @Test
    void updatingAMissingSessionChangesNothing() {
        assertThat(repo.update(SessionId.random(), x -> x.withTitle("never"))).isEmpty();
    }
}
