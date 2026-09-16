package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FileUserRepositoryTest {

    @TempDir
    Path dir;

    private static User user(String id) {
        Instant at = Instant.parse("2026-09-11T12:00:00Z");
        return new User(UserId.of(id), "sub-" + id, "https://auth/realms/mc", "Name " + id, id + "@example.com", at, at);
    }

    @Test
    void aUserSurvivesTheRoundTripInTheSystemDirectory() {
        var repo = new FileUserRepository(dir);
        User alice = user("alice");
        repo.save(alice);

        assertThat(repo.findById(UserId.of("alice"))).contains(alice);
        assertThat(repo.findAll()).containsExactly(alice);
        assertThat(dir.resolve("system/users")).isDirectoryContaining("glob:**alice-*.json");
    }

    @Test
    void theUsersVariablesSurviveTheRoundTrip_andAnOldRecordWithoutThemReadsAsEmpty() throws Exception {
        var repo = new FileUserRepository(dir);
        User alice = user("alice").withEnvironment(java.util.Map.of("OPENAI_API_KEY", "enc:abc"));
        repo.save(alice);

        assertThat(repo.findById(UserId.of("alice"))).contains(alice);

        java.nio.file.Path file = java.nio.file.Files.list(dir.resolve("system/users")).findFirst().orElseThrow();
        java.nio.file.Files.writeString(file, java.nio.file.Files.readString(file).replaceAll(",?\\s*\"environment\"\\s*:\\s*\\{[^}]*\\}", ""));
        assertThat(repo.findById(UserId.of("alice"))).get().extracting(User::environment).isEqualTo(java.util.Map.of());
    }

    @Test
    void idsThatAreNoFileNamesAndDifferOnlyInCaseStayApart() {
        var repo = new FileUserRepository(dir);
        repo.save(user("Alice"));
        repo.save(user("alice"));
        repo.save(user("alice.smith@example.com"));
        repo.save(user("../../etc/passwd"));

        assertThat(repo.findAll()).hasSize(4);
        assertThat(repo.findById(UserId.of("Alice"))).map(User::displayName).contains("Name Alice");
        assertThat(repo.findById(UserId.of("alice"))).map(User::displayName).contains("Name alice");
        assertThat(repo.findById(UserId.of("../../etc/passwd"))).isPresent();
        assertThat(Files.exists(dir.resolve("etc"))).isFalse();
    }

    @Test
    void anUnreadableFileIsSkipped() throws Exception {
        var repo = new FileUserRepository(dir);
        repo.save(user("alice"));
        Files.writeString(dir.resolve("system/users/broken-000000000000.json"), "{not json");

        assertThat(repo.findAll()).extracting(User::id).containsExactly(UserId.of("alice"));
    }
}
