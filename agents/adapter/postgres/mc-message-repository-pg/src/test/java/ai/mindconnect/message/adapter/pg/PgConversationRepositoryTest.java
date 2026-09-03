package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationStatus;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Participant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PgConversationRepositoryTest {

    private static final Namespace NS = new Namespace("team-a");

    private PgConversationRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_conversation");
        repo = new PgConversationRepository(sql).initSchema();
    }

    private static Conversation at(Namespace ns, String topic, String createdAt) {
        Instant t = Instant.parse(createdAt);
        UUID id = UUID.randomUUID();
        return new Conversation(id, ns, topic, ConversationType.USER_AGENT,
                ConversationStatus.OPEN, List.of(Participant.user(id, "david", "David")), t, t);
    }

    @Test
    void aConversationSurvivesTheRoundTrip() {
        Conversation c = at(NS, "hello", "2026-09-03T10:00:00.123456Z");
        assertThat(repo.save(c)).isSameAs(c);
        assertThat(repo.findById(c.id())).contains(c);
        assertThat(repo.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void savingAgainReplacesTheDocument() {
        Conversation c = at(NS, "draft", "2026-09-03T10:00:00Z");
        repo.save(c);
        Conversation closed = new Conversation(c.id(), c.namespace(), "final", c.type(),
                ConversationStatus.CLOSED, c.participants(), c.createdAt(), Instant.parse("2026-09-03T11:00:00Z"));
        repo.save(closed);
        assertThat(repo.findById(c.id())).contains(closed);
        assertThat(repo.findByNamespace(NS, PageRequest.DEFAULT)).hasSize(1);
    }

    @Test
    void findByNamespaceIsNewestFirstPagedAndScopedToTheNamespace() {
        Conversation oldest = at(NS, "oldest", "2026-09-01T00:00:00Z");
        Conversation middle = at(NS, "middle", "2026-09-02T00:00:00Z");
        Conversation newest = at(NS, "newest", "2026-09-03T00:00:00Z");
        Conversation elsewhere = at(new Namespace("team-b"), "elsewhere", "2026-09-04T00:00:00Z");
        for (Conversation c : List.of(middle, oldest, elsewhere, newest)) repo.save(c);

        assertThat(repo.findByNamespace(NS, new PageRequest(0, 2))).containsExactly(newest, middle);
        assertThat(repo.findByNamespace(NS, new PageRequest(1, 2))).containsExactly(oldest);
        assertThat(repo.findByNamespace(NS, new PageRequest(2, 2))).isEmpty();
        assertThat(repo.findByNamespace(new Namespace("team-b"), PageRequest.DEFAULT)).containsExactly(elsewhere);
    }
}
