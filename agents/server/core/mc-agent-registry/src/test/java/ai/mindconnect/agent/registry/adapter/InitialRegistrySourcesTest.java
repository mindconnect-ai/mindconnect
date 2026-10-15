package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.registry.adapter.file.FileRegistrySourceRepository;
import ai.mindconnect.agent.registry.adapter.initialdata.InitialRegistrySources;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application ships its registries as files under
 * {@code initial-data/registries/}; a start installs the ones not configured yet.
 */
class InitialRegistrySourcesTest {

    private static final String LOCATION = "classpath*:initial-data-test/registries/*.json";
    private static final RegistrySourceId ID = RegistrySourceId.of("acme-agents");

    @Test
    void a_shipped_registry_is_installed_under_its_file_name(@TempDir Path dir) {
        FileRegistrySourceRepository repository = new FileRegistrySourceRepository(dir, Namespace.DEFAULT);

        assertThat(InitialRegistrySources.install(repository, LOCATION)).containsExactly("acme-agents");

        RegistrySource source = repository.findById(ID).orElseThrow();
        assertThat(source.coordinates()).isEqualTo("acme/agents@v1.0.0");
        assertThat(source.enabled()).isTrue();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void a_registry_already_configured_keeps_what_the_operator_made_of_it(@TempDir Path dir) {
        FileRegistrySourceRepository repository = new FileRegistrySourceRepository(dir, Namespace.DEFAULT);
        InitialRegistrySources.install(repository, LOCATION);
        repository.save(repository.findById(ID).orElseThrow().withEnabled(false));

        assertThat(InitialRegistrySources.install(repository, LOCATION)).isEmpty();

        assertThat(repository.findById(ID).orElseThrow().enabled()).isFalse();
    }
}
