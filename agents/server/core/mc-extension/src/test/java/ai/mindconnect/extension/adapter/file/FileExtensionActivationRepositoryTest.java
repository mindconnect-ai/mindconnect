package ai.mindconnect.extension.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileExtensionActivationRepositoryTest {

    private static final ExtensionId ACME = ExtensionId.of("acme-crm");

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void a_decision_is_one_document_in_the_namespace_s_system_directory() throws Exception {
        var repo = new FileExtensionActivationRepository(dir, mapper, new Namespace("acme"));

        repo.save(ExtensionActivation.of(ACME, false, UserId.of("david")));

        Path file = dir.resolve("acme/system/extensions/acme-crm.json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"extensionId\" : \"acme-crm\"").contains("\"enabled\" : false");
        assertThat(repo.find(ACME)).isPresent().get().extracting(ExtensionActivation::changedBy).isEqualTo(UserId.of("david"));
        assertThat(repo.all()).hasSize(1);
    }

    @Test
    void namespaces_do_not_see_each_other_s_decisions() {
        var acme = new FileExtensionActivationRepository(dir, mapper, new Namespace("acme"));
        var other = new FileExtensionActivationRepository(dir, mapper, new Namespace("other"));

        acme.save(ExtensionActivation.of(ACME, false, null));

        assertThat(other.find(ACME)).isEmpty();
        assertThat(other.all()).isEmpty();
    }

    @Test
    void deleting_forgets_and_an_empty_directory_is_no_decision() {
        var repo = new FileExtensionActivationRepository(dir, mapper, new Namespace("acme"));
        assertThat(repo.find(ACME)).isEmpty();

        repo.save(ExtensionActivation.of(ACME, true, null));
        repo.delete(ACME);

        assertThat(repo.find(ACME)).isEmpty();
        assertThat(repo.all()).isEmpty();
    }
}
