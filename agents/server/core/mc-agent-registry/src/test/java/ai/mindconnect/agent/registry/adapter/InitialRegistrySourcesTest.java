package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.registry.adapter.file.FileRegistrySourceRepository;
import ai.mindconnect.agent.registry.adapter.initialdata.InitialRegistrySources;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.jackson.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
        FileRegistrySourceRepository repository = new FileRegistrySourceRepository(dir, ScopeSupplier.fixed(Namespace.DEFAULT), new JsonMapperFactory(List.of()));

        assertThat(InitialRegistrySources.install(repository, LOCATION, new JsonMapperFactory(List.of()))).containsExactly("acme-agents");

        RegistrySource source = repository.findById(ID).orElseThrow();
        assertThat(source.coordinates()).isEqualTo("acme/agents@v1.0.0");
        assertThat(source.enabled()).isTrue();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void a_registry_already_configured_keeps_what_the_operator_made_of_it(@TempDir Path dir) {
        FileRegistrySourceRepository repository = new FileRegistrySourceRepository(dir, ScopeSupplier.fixed(Namespace.DEFAULT), new JsonMapperFactory(List.of()));
        InitialRegistrySources.install(repository, LOCATION, new JsonMapperFactory(List.of()));
        repository.save(repository.findById(ID).orElseThrow().withEnabled(false));

        assertThat(InitialRegistrySources.install(repository, LOCATION, new JsonMapperFactory(List.of()))).isEmpty();

        assertThat(repository.findById(ID).orElseThrow().enabled()).isFalse();
    }
}
