package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ConversationStatus;
import ai.mindconnect.message.domain.ConversationType;
import ai.mindconnect.message.domain.Participant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PgConversationRepositoryTest {

    private static final Namespace NS = new Namespace("test");

    private PgConversationRepository repo;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_conversation");
        repo = new PgConversationRepository(sql, NS).initSchema();
    }

    private static Conversation at(String topic, String createdAt) {
        Instant t = Instant.parse(createdAt);
        ConversationId id = ConversationId.random();
        return new Conversation(id, topic, ConversationType.USER_AGENT,
                ConversationStatus.OPEN, List.of(Participant.user(id, UserId.of("david"), "David")), t, t);
    }

    @Test
    void aConversationSurvivesTheRoundTrip() {
        Conversation c = at("hello", "2026-09-03T10:00:00.123456Z");
        assertThat(repo.save(c)).isSameAs(c);
        assertThat(repo.findById(c.id())).contains(c);
        assertThat(repo.findById(ConversationId.random())).isEmpty();
    }

    @Test
    void savingAgainReplacesTheDocument() {
        Conversation c = at("draft", "2026-09-03T10:00:00Z");
        repo.save(c);
        Conversation closed = new Conversation(c.id(), "final", c.type(),
                ConversationStatus.CLOSED, c.participants(), c.createdAt(), Instant.parse("2026-09-03T11:00:00Z"));
        repo.save(closed);
        assertThat(repo.findById(c.id())).contains(closed);
        assertThat(repo.findAll(PageRequest.DEFAULT)).hasSize(1);
    }

    @Test
    void findAllIsNewestFirstAndPaged() {
        Conversation oldest = at("oldest", "2026-09-01T00:00:00Z");
        Conversation middle = at("middle", "2026-09-02T00:00:00Z");
        Conversation newest = at("newest", "2026-09-03T00:00:00Z");
        for (Conversation c : List.of(middle, oldest, newest)) repo.save(c);

        assertThat(repo.findAll(new PageRequest(0, 2))).containsExactly(newest, middle);
        assertThat(repo.findAll(new PageRequest(1, 2))).containsExactly(oldest);
        assertThat(repo.findAll(new PageRequest(2, 2))).isEmpty();
    }
}
