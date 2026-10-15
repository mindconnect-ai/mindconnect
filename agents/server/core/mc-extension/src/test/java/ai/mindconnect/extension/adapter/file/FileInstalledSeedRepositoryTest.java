package ai.mindconnect.extension.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.extension.domain.InstalledSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileInstalledSeedRepositoryTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void the_installed_seeds_are_one_document_in_the_namespace_s_system_directory() throws Exception {
        var repo = new FileInstalledSeedRepository(dir, mapper, new Namespace("acme"));

        repo.record(List.of(InstalledSeed.of("agent", "My agent", "acme-crm"),
                InstalledSeed.of("llm-config", "claude-default", null)));

        Path file = dir.resolve("acme/system/installed-seeds.json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"name\" : \"My agent\"").contains("\"source\" : \"host\"");
        assertThat(repo.keys()).containsExactlyInAnyOrder("agent:My agent", "llm-config:claude-default");
    }

    @Test
    void a_key_recorded_before_keeps_its_first_entry() {
        var repo = new FileInstalledSeedRepository(dir, mapper, new Namespace("acme"));

        repo.record(List.of(InstalledSeed.of("agent", "email-assistant", "office")));
        repo.record(List.of(InstalledSeed.of("agent", "email-assistant", "other"),
                InstalledSeed.of("skill", "pptx-builder", null)));

        assertThat(repo.all()).hasSize(2);
        assertThat(repo.all()).filteredOn(seed -> seed.name().equals("email-assistant"))
                .extracting(InstalledSeed::source).containsExactly("office");
    }

    @Test
    void namespaces_do_not_see_each_other_s_seeds() {
        var acme = new FileInstalledSeedRepository(dir, mapper, new Namespace("acme"));
        var other = new FileInstalledSeedRepository(dir, mapper, new Namespace("other"));

        acme.record(List.of(InstalledSeed.of("agent", "email-assistant", "office")));

        assertThat(other.all()).isEmpty();
        assertThat(new FileInstalledSeedRepository(dir, mapper, new Namespace("acme")).keys())
                .as("read back by a fresh adapter — after a restart")
                .containsExactly("agent:email-assistant");
    }
}
