package ai.mindconnect.agent.registry.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.domain.RegistrySourceId;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgRegistrySourceRepositoryTest {

    private Sql sql;
    private PgRegistrySourceRepository acme;
    private PgRegistrySourceRepository other;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.require());
        sql.execute("DROP TABLE IF EXISTS mc_registry_source");
        acme = new PgRegistrySourceRepository(sql, new Namespace("acme")).initSchema();
        other = new PgRegistrySourceRepository(sql, new Namespace("other")).initSchema();
    }

    @Test
    void a_saved_registry_reads_back_with_everything_it_was_given() {
        RegistrySource source = new RegistrySource(RegistrySource.of("acme/agents").id(),
                "Acme agents", "acme", "agents", "v2", "catalog/index.json", "ACME_TOKEN",
                "https://ghe.example.com/raw", false, null);

        RegistrySource saved = acme.save(source);

        assertThat(saved.version()).isEqualTo(1L);
        assertThat(acme.findById(source.id())).contains(saved);
        assertThat(acme.findAll()).containsExactly(saved);
        assertThat(acme.findEnabled()).isEmpty();
    }

    @Test
    void a_save_checks_the_version_it_was_read_with() {
        RegistrySource first = acme.save(RegistrySource.of("acme/agents"));
        RegistrySource second = acme.save(first.withEnabled(false));

        assertThat(second.version()).isEqualTo(2L);
        assertThatThrownBy(() -> acme.save(first.withEnabled(true)))
                .isInstanceOf(StaleVersionException.class);
        assertThat(acme.findById(first.id()).orElseThrow().enabled()).isFalse();
    }

    @Test
    void registries_are_listed_by_name_regardless_of_case() {
        acme.save(RegistrySource.of("zeta/one"));
        acme.save(RegistrySource.of("Alpha/two"));
        acme.save(RegistrySource.of("beta/three"));

        assertThat(acme.findAll()).extracting(RegistrySource::name)
                .containsExactly("Alpha/two", "beta/three", "zeta/one");
    }

    @Test
    void deleting_one_forgets_it() {
        RegistrySource saved = acme.save(RegistrySource.of("acme/agents"));

        acme.deleteById(saved.id());

        assertThat(acme.findById(saved.id())).isEmpty();
        assertThat(acme.findAll()).isEmpty();
    }

    @Test
    void a_namespace_sees_only_its_own_registries() {
        RegistrySource source = RegistrySource.of("acme/agents");
        acme.save(source);
        other.save(source.withEnabled(false));

        assertThat(acme.findById(source.id()).orElseThrow().enabled()).isTrue();
        assertThat(other.findById(source.id()).orElseThrow().enabled()).isFalse();

        other.deleteById(source.id());

        assertThat(other.findAll()).isEmpty();
        assertThat(acme.findAll()).hasSize(1);
    }

    @Test
    void the_files_of_a_file_store_are_imported_once_and_kept(@TempDir Path dir) throws Exception {
        // As production has it: the shipped registry, seeded into the file store.
        Path shipped = dir.resolve("mindconnect-ai-mc-registry.json");
        Files.writeString(shipped, """
                {
                  "id" : "mindconnect-ai-mc-registry",
                  "name" : "Mindconnect registry",
                  "owner" : "mindconnect-ai",
                  "repo" : "mc-registry",
                  "ref" : "main",
                  "indexPath" : "registry.json",
                  "tokenEnvVar" : null,
                  "baseUrl" : "https://raw.githubusercontent.com",
                  "enabled" : true,
                  "version" : 3,
                  "somethingNewer" : "ignored"
                }
                """);
        // Written by hand, without an id: the file name is the id.
        Files.writeString(dir.resolve("private-agents.json"), """
                {"owner": "acme", "repo": "private-agents", "ref": "v1.2.0", "tokenEnvVar": "ACME_TOKEN", "enabled": false}
                """);
        Files.writeString(dir.resolve("broken.json"), "not json");
        Files.writeString(dir.resolve("notes.txt"), "not a registry");

        assertThat(acme.importFiles(dir)).isEqualTo(2);

        RegistrySource imported = acme.findById(RegistrySourceId.of("mindconnect-ai-mc-registry")).orElseThrow();
        assertThat(imported.name()).isEqualTo("Mindconnect registry");
        assertThat(imported.coordinates()).isEqualTo("mindconnect-ai/mc-registry@main");
        assertThat(imported.version()).isEqualTo(3L);
        RegistrySource byHand = acme.findById(RegistrySourceId.of("private-agents")).orElseThrow();
        assertThat(byHand.ref()).isEqualTo("v1.2.0");
        assertThat(byHand.tokenEnvVar()).isEqualTo("ACME_TOKEN");
        assertThat(byHand.enabled()).isFalse();
        assertThat(shipped).exists();
        assertThat(other.findAll()).isEmpty();

        // Once: what was deleted since stays deleted, however often it is asked.
        acme.deleteById(byHand.id());
        assertThat(acme.importFiles(dir)).isZero();
        assertThat(acme.findAll()).extracting(RegistrySource::id)
                .containsExactly(RegistrySourceId.of("mindconnect-ai-mc-registry"));

        // A save after the import is checked against the imported version.
        assertThat(acme.save(imported.withEnabled(false)).version()).isEqualTo(4L);
    }

    @Test
    void a_namespace_that_has_registries_already_imports_nothing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("acme-agents.json"), """
                {"id": "acme-agents", "owner": "acme", "repo": "agents"}
                """);
        acme.save(RegistrySource.of("other/registry"));

        assertThat(acme.importFiles(dir)).isZero();
        assertThat(acme.findAll()).extracting(RegistrySource::owner).containsExactly("other");

        assertThat(other.importFiles(dir)).isEqualTo(1);
    }

    @Test
    void no_directory_imports_nothing(@TempDir Path dir) {
        assertThat(acme.importFiles(dir.resolve("missing"))).isZero();
        assertThat(acme.importFiles(null)).isZero();
        assertThat(acme.findAll()).isEmpty();
    }
}
