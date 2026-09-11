package ai.mindconnect.user.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PgApiTokenRepositoryTest {

    private static final Namespace NS = new Namespace("test");
    private static final Instant AT = Instant.parse("2026-09-11T12:00:00Z");

    private Sql sql;
    private PgApiTokenRepository repo;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_api_token");
        repo = new PgApiTokenRepository(sql, NS).initSchema();
    }

    private static ApiToken token(String owner, String hash) {
        return new ApiToken(ApiTokenId.random(), UserId.of(owner), "ci", hash, "mct_abcdef",
                AT, AT.plusSeconds(86_400), null);
    }

    @Test
    void aTokenIsFoundByIdHashAndOwner() {
        ApiToken alices = token("alice", "hash-a");
        ApiToken bobs = token("bob", "hash-b");
        repo.save(alices);
        repo.save(bobs);

        assertThat(repo.findById(alices.id())).contains(alices);
        assertThat(repo.findByHash("hash-b")).contains(bobs);
        assertThat(repo.findByHash("nope")).isEmpty();
        assertThat(repo.findByUser(UserId.of("alice"))).containsExactly(alices);
    }

    @Test
    void aRepositoryBoundToAnotherNamespaceSeesNothing() {
        ApiToken alices = token("alice", "hash-a");
        repo.save(alices);
        var other = new PgApiTokenRepository(sql, new Namespace("other")).initSchema();

        assertThat(other.findById(alices.id())).isEmpty();
        assertThat(other.findByHash("hash-a")).isEmpty();
        assertThat(other.findByUser(UserId.of("alice"))).isEmpty();
    }

    @Test
    void recordingAUseUpdatesATokenButNeverBringsARevokedOneBack() {
        ApiToken alices = token("alice", "hash-a");
        repo.save(alices);

        Instant used = AT.plusSeconds(60);
        repo.recordUse(alices.id(), used);
        assertThat(repo.findById(alices.id())).map(ApiToken::lastUsedAt).contains(used);

        repo.deleteById(alices.id());
        repo.recordUse(alices.id(), used.plusSeconds(60));
        assertThat(repo.findById(alices.id())).isEmpty();
        assertThat(repo.findByHash("hash-a")).isEmpty();
    }
}
