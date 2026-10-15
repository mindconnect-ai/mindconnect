package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePreferenceRepositoryTest {

    private static final Instant AT = Instant.parse("2026-09-21T10:00:00Z");

    @TempDir
    Path dir;

    @Test
    void a_scope_is_one_file_in_the_users_directory_and_survives_a_new_repository() throws Exception {
        var repo = new FilePreferenceRepository(dir);
        UserId alice = UserId.of("alice@example.com");
        Preferences email = new Preferences(alice, "office.email", Map.of("folder", "INBOX"), AT);
        repo.save(email);
        repo.save(new Preferences(alice, "office.calendar", Map.of("view", "week"), AT));
        repo.save(new Preferences(UserId.of("bob"), "office.email", Map.of("folder", "Sent"), AT));

        var again = new FilePreferenceRepository(dir);
        assertThat(again.find(alice, "office.email")).contains(email);
        assertThat(again.findByUser(alice)).hasSize(2);
        assertThat(Files.exists(dir.resolve("system/preferences/"
                + FileUserRepository.fileKey(alice) + "/office.email.json"))).isTrue();

        // What it holds, and nothing a getter happened to be called.
        assertThat(Files.readString(dir.resolve("system/preferences/"
                + FileUserRepository.fileKey(alice) + "/office.email.json"))).doesNotContain("\"empty\"");

        again.delete(alice, "office.email");
        assertThat(again.find(alice, "office.email")).isEmpty();
        assertThat(again.find(UserId.of("bob"), "office.email")).isPresent();
    }

    @Test
    void a_scope_never_becomes_a_path_it_could_escape_through() {
        var repo = new FilePreferenceRepository(dir);

        assertThatThrownBy(() -> repo.find(UserId.of("alice"), "../../x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
