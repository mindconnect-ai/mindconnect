package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry in Postgres behaves as the one on files: templates versioned,
 * instances registered once, names found case-insensitively — per namespace,
 * and filled once from the files a namespace had before.
 */
class PgVectorStoreRegistryTest {

    private static final Namespace ACME = new Namespace("pgreg-acme");
    private static final Namespace OTHER = new Namespace("pgreg-other");

    private DataSource db;
    private Sql sql;

    @BeforeEach
    void setUp() {
        db = TestDb.requirePostgres();
        sql = Sql.of(db);
        new PgVectorStoreRegistry(sql, ACME).initSchema();
        TestDb.forget(db, ACME);
        TestDb.forget(db, OTHER);
    }

    @Test
    void templatesAndInstancesRoundTrip() {
        PgVectorStoreRegistry registry = new PgVectorStoreRegistry(sql, ACME);
        VectorStoreTemplate template = new VectorStoreTemplate("Chat Uploads", "pgvector", Map.of(),
                "embeddings", null, Map.of("description", "per chat"));

        VectorStoreTemplate saved = registry.saveTemplate(template.withVersion(0L));
        VectorStoreInstance instance = registry.registerInstance(VectorStoreInstance.fromTemplate(
                "session-s1", template, VectorStoreInstance.Scope.SESSION, "s1", "alice"));

        assertThat(saved.version()).isEqualTo(1L);
        assertThat(registry.template("chat-uploads")).as("found by its key, as on files").contains(saved);
        assertThat(registry.templates()).containsExactly(saved);
        assertThat(registry.instance("session-s1")).contains(instance);
        assertThat(registry.instances(VectorStoreInstance.Scope.SESSION, "s1")).containsExactly(instance);
        assertThat(registry.instances(VectorStoreInstance.Scope.SESSION, null)).containsExactly(instance);
        assertThat(registry.instances(VectorStoreInstance.Scope.GLOBAL, null)).isEmpty();

        assertThatThrownBy(() -> registry.saveTemplate(template.withVersion(0L)))
                .as("a new template does not overwrite one of the same name")
                .isInstanceOf(StaleVersionException.class);
        assertThat(registry.saveTemplate(saved).version()).isEqualTo(2L);

        VectorStoreInstance moved = instance.onBackend("memory");
        registry.saveInstance(moved);
        assertThat(registry.instance("session-s1")).contains(moved);

        registry.deleteInstance("session-s1");
        registry.deleteTemplate("Chat Uploads");
        assertThat(registry.instances()).isEmpty();
        assertThat(registry.templates()).isEmpty();
    }

    @Test
    void anExistingInstanceWins_evenWhenRegisteredConcurrently() throws Exception {
        List<Future<VectorStoreInstance>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                PgVectorStoreRegistry registry = new PgVectorStoreRegistry(sql, ACME);
                VectorStoreInstance candidate = new VectorStoreInstance("session-s1", "chat-uploads", "pgvector",
                        Map.of(), "embeddings", null, Map.of("attempt", Integer.toString(i)),
                        VectorStoreInstance.Scope.SESSION, "s1", "alice", Instant.now());
                futures.add(pool.submit(() -> registry.registerInstance(candidate)));
            }
        }

        VectorStoreInstance first = futures.get(0).get();
        for (Future<VectorStoreInstance> future : futures) {
            assertThat(future.get()).isEqualTo(first);
        }
        assertThat(new PgVectorStoreRegistry(sql, ACME).instances()).containsExactly(first);
    }

    @Test
    void aNamespaceSeesOnlyItsOwnRecords() {
        PgVectorStoreRegistry acme = new PgVectorStoreRegistry(sql, ACME);
        PgVectorStoreRegistry other = new PgVectorStoreRegistry(sql, OTHER);
        VectorStoreTemplate template = new VectorStoreTemplate("knowledge", "pgvector", Map.of(),
                "embeddings", null, Map.of());

        acme.saveTemplate(template);
        acme.registerInstance(VectorStoreInstance.fromTemplate("docs", template, VectorStoreInstance.Scope.GLOBAL, null));

        assertThat(other.templates()).isEmpty();
        assertThat(other.template("knowledge")).isEmpty();
        assertThat(other.instance("docs")).isEmpty();
        assertThat(other.saveTemplate(template).version()).as("the same name, another record").isEqualTo(1L);
        other.deleteTemplate("knowledge");
        other.deleteInstance("docs");
        assertThat(acme.template("knowledge")).isPresent();
        assertThat(acme.instance("docs")).isPresent();
    }

    @Test
    void theFilesAreImportedOnce_andStay(@TempDir Path dir) {
        FileVectorStoreRegistry files = new FileVectorStoreRegistry(dir.resolve("vector-stores"));
        VectorStoreTemplate template = files.saveTemplate(new VectorStoreTemplate("chat-uploads", "memory",
                Map.of(), "embeddings", "file-ingestion", Map.of()));
        VectorStoreInstance instance = files.registerInstance(VectorStoreInstance.fromTemplate(
                "session-s1", template, VectorStoreInstance.Scope.SESSION, "s1", "alice"));
        PgVectorStoreRegistry registry = new PgVectorStoreRegistry(sql, ACME);

        assertThat(registry.importFrom(files)).isEqualTo(2);

        assertThat(registry.templates()).containsExactly(template);
        assertThat(registry.instances()).containsExactly(instance);
        assertThat(new PgVectorStoreRegistry(sql, OTHER).instances()).isEmpty();
        assertThat(Files.exists(dir.resolve("vector-stores/templates/chat-uploads.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("vector-stores/instances/session-s1.json"))).isTrue();

        files.saveTemplate(new VectorStoreTemplate("knowledge", "memory", Map.of(), "embeddings", null, Map.of()));
        assertThat(registry.importFrom(files)).as("the namespace has records now").isZero();
        assertThat(registry.template("knowledge")).isEmpty();
    }
}
