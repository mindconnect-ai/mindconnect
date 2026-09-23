package ai.mindconnect.extension.adapter.file;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileBrandActivationRepositoryTest {

    private static final ExtensionId ACME = ExtensionId.of("acme-crm");

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void a_brand_s_decision_is_one_document_in_the_system_partition() throws Exception {
        var repo = new FileBrandActivationRepository(dir, mapper);

        repo.save(BrandActivation.of("erni", ACME, false, true, UserId.of("david")));

        Path file = dir.resolve("system/extension-brands/erni/acme-crm.json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("\"brand\" : \"erni\"").contains("\"locked\" : true");
        assertThat(repo.find("erni", ACME)).isPresent().get().extracting(BrandActivation::enabled).isEqualTo(false);
        assertThat(repo.find("other", ACME)).isEmpty();
        assertThat(repo.all("erni")).hasSize(1);
        assertThat(repo.all("other")).isEmpty();
    }

    @Test
    void deleting_forgets() {
        var repo = new FileBrandActivationRepository(dir, mapper);
        repo.save(BrandActivation.of("erni", ACME, true, false, null));
        repo.delete("erni", ACME);

        assertThat(repo.find("erni", ACME)).isEmpty();
    }

    @Test
    void a_brand_that_is_no_namespace_id_is_refused() {
        var repo = new FileBrandActivationRepository(dir, mapper);

        assertThatThrownBy(() -> repo.save(BrandActivation.of("../etc", ACME, true, false, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
