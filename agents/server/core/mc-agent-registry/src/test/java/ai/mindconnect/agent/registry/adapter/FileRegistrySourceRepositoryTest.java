package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.registry.adapter.file.FileRegistrySourceRepository;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileRegistrySourceRepositoryTest {

    @Test
    void a_saved_registry_reads_back_with_everything_it_was_given(@TempDir Path dir) {
        FileRegistrySourceRepository repository =
                new FileRegistrySourceRepository(dir, Namespace.DEFAULT);
        RegistrySource source = new RegistrySource(RegistrySource.of("acme/agents").id(),
                "Acme agents", "acme", "agents", "v2", "catalog/index.json", "ACME_TOKEN",
                null, true, null);

        repository.save(source);

        RegistrySource read = repository.findById(source.id()).orElseThrow();
        assertThat(read.owner()).isEqualTo("acme");
        assertThat(read.ref()).isEqualTo("v2");
        assertThat(read.indexPath()).isEqualTo("catalog/index.json");
        assertThat(read.tokenEnvVar()).isEqualTo("ACME_TOKEN");
        assertThat(read.version()).isNotNull();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findEnabled()).hasSize(1);
    }

    @Test
    void a_disabled_registry_stays_configured_but_out_of_the_enabled_list(@TempDir Path dir) {
        FileRegistrySourceRepository repository =
                new FileRegistrySourceRepository(dir, Namespace.DEFAULT);
        RegistrySource saved = repository.save(RegistrySource.of("acme/agents"));

        repository.save(saved.withEnabled(false));

        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findEnabled()).isEmpty();
    }

    @Test
    void deleting_one_leaves_the_store_empty(@TempDir Path dir) {
        FileRegistrySourceRepository repository =
                new FileRegistrySourceRepository(dir, Namespace.DEFAULT);
        RegistrySource saved = repository.save(RegistrySource.of("acme/agents"));

        repository.deleteById(saved.id());

        assertThat(repository.findAll()).isEmpty();
    }
}
