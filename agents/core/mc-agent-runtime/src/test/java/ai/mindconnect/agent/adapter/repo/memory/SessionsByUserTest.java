package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.common.Namespace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the chat's sidebar asks for: this user's own conversations, newest
 * first, without the sub-agent sessions their turns spawned.
 */
class SessionsByUserTest {

    private static final Namespace NS = new Namespace("local");
    private final InMemoryAgentSessionRepository repo = new InMemoryAgentSessionRepository();

    private AgentSession save(String user, Instant startedAt, UUID parent) {
        var base = AgentSession.startSubAgent(UUID.randomUUID(), NS, user, UUID.randomUUID(),
                parent, null, parent == null ? null : "call-1");
        var withTime = new AgentSession(base.id(), base.agentDefinitionId(), base.namespace(),
                base.userId(), base.conversationId(), base.title(), base.status(),
                startedAt, base.completedAt(), base.parentSessionId(), base.parentTurnId(),
                base.parentToolCallId(), base.activatedTools(), base.attachedFiles(),
                base.approvedTools(), base.sessionAgents());
        return repo.save(withTime);
    }

    @Test
    void newestFirst_ownUserOnly_andNoSubAgentSessions() {
        var older = save("alice", Instant.parse("2026-08-01T10:00:00Z"), null);
        var newer = save("alice", Instant.parse("2026-08-29T10:00:00Z"), null);
        save("alice", Instant.parse("2026-08-29T11:00:00Z"), newer.id());   // sub-agent
        save("bob",   Instant.parse("2026-08-29T12:00:00Z"), null);         // someone else

        assertThat(repo.findByUser(NS, "alice"))
                .extracting(AgentSession::id)
                .containsExactly(newer.id(), older.id());
    }

    @Test
    void aSessionWithoutAStartTimeDoesNotTakeTheWholeListDown() {
        var dated = save("alice", Instant.parse("2026-08-29T10:00:00Z"), null);
        var undated = save("alice", null, null);

        // One unreadable timestamp should misplace a row, not throw — the
        // sidebar is the user's whole history.
        assertThat(repo.findByUser(NS, "alice"))
                .extracting(AgentSession::id)
                .containsExactly(dated.id(), undated.id());
    }

    @Test
    void noSessionsIsAnEmptyList() {
        assertThat(repo.findByUser(NS, "nobody")).isEmpty();
    }
}
