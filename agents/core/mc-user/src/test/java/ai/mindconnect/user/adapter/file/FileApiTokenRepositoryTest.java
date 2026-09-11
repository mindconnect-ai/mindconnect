package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileApiTokenRepositoryTest {

    private static final Instant AT = Instant.parse("2026-09-11T12:00:00Z");

    @TempDir
    Path dir;

    private static ApiToken token(String owner, String hash) {
        return new ApiToken(ApiTokenId.random(), UserId.of(owner), "ci", hash, "mct_abcdef", AT, null, null);
    }

    @Test
    void aTokenIsFoundByIdHashAndOwner() {
        var repo = new FileApiTokenRepository(dir, new Namespace("test"));
        ApiToken alices = token("alice", "hash-a");
        ApiToken bobs = token("bob", "hash-b");
        repo.save(alices);
        repo.save(bobs);

        assertThat(repo.findById(alices.id())).contains(alices);
        assertThat(repo.findByHash("hash-b")).contains(bobs);
        assertThat(repo.findByHash("nope")).isEmpty();
        assertThat(repo.findByUser(UserId.of("alice"))).containsExactly(alices);
        assertThat(dir.resolve("test/system/api-tokens/" + alices.id().value() + ".json")).exists();
    }

    @Test
    void anotherNamespaceSeesNothing() {
        ApiToken alices = token("alice", "hash-a");
        new FileApiTokenRepository(dir, new Namespace("test")).save(alices);

        var other = new FileApiTokenRepository(dir, new Namespace("other"));
        assertThat(other.findById(alices.id())).isEmpty();
        assertThat(other.findByHash("hash-a")).isEmpty();
    }

    @Test
    void recordingAUseUpdatesATokenButNeverBringsARevokedOneBack() {
        var repo = new FileApiTokenRepository(dir, new Namespace("test"));
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
