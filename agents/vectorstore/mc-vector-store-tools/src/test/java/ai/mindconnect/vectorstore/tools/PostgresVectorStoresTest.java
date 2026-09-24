package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under Postgres persistence the vector stores keep their registry in the
 * runtime's database, and their vectors there too when it has pgvector —
 * without a URL of their own. A database without pgvector (a plain
 * {@code postgres} image) keeps the vectors on the memory backend, in files,
 * and everything still works.
 */
class PostgresVectorStoresTest {

    private static final Namespace NS = new Namespace("pgvs-choice");

    @TempDir
    Path dir;

    @Test
    void withPgvector_theVectorsLiveInTheRuntimesDatabase() {
        DataSource db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();

        assertThat(stores.template(NS, VectorStores.DEFAULT_TEMPLATE).orElseThrow().backend()).isEqualTo("pgvector");
        VectorStore store = stores.open(NS, "kb", VectorStores.DEFAULT_TEMPLATE, VectorStoreInstance.Scope.GLOBAL, null);
        store.upsert(List.of(new VectorChunk("doc1:0", "doc1", 0, "podman is a container engine", Map.of(),
                new float[]{1f, 0f, 0f})));

        Sql sql = Sql.of(db);
        assertThat(sql.scalar("SELECT count(*) FROM vs_pgvs_choice__kb", Long.class)).isEqualTo(1L);
        assertThat(sql.scalar("SELECT count(*) FROM mc_vector_store_instance WHERE namespace = ? AND name = 'kb'",
                Long.class, NS.value())).isEqualTo(1L);
        assertThat(stores.discoverStores(NS, "pgvector", Map.of())).containsExactly("kb");
        assertThat(Files.exists(dir.resolve(NS.value()))).as("nothing of the stores is kept in files").isFalse();
    }

    @Test
    void withoutPgvector_theVectorsStayInFiles_andTheRegistryInTheDatabase() {
        DataSource db = TestDb.withoutPgvector();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();

        assertThat(stores.template(NS, VectorStores.DEFAULT_TEMPLATE).orElseThrow().backend()).isEqualTo("memory");
        VectorStore store = stores.open(NS, "kb", VectorStores.DEFAULT_TEMPLATE, VectorStoreInstance.Scope.GLOBAL, null);
        store.upsert(List.of(new VectorChunk("doc1:0", "doc1", 0, "podman is a container engine", Map.of(),
                new float[]{1f, 0f, 0f})));

        assertThat(store.search(new float[]{1f, 0f, 0f}, 1)).hasSize(1);
        assertThat(dir.resolve(NS.value()).resolve("vector-stores/kb.jsonl")).exists();
        assertThat(dir.resolve(NS.value()).resolve("vector-stores/instances")).doesNotExist();
        assertThat(Sql.of(db).scalar("SELECT count(*) FROM mc_vector_store_instance WHERE namespace = ? AND name = 'kb'",
                Long.class, NS.value())).isEqualTo(1L);
        assertThat(stores.registry(NS).instance("kb").orElseThrow().backend()).isEqualTo("memory");
    }

    @Test
    void aBackendTheHostNamesIsKept() {
        DataSource db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir,
                Map.of("vectorStoreBackend", "memory"))).orElseThrow();

        assertThat(stores.template(NS, VectorStores.DEFAULT_TEMPLATE).orElseThrow().backend()).isEqualTo("memory");
    }
}
