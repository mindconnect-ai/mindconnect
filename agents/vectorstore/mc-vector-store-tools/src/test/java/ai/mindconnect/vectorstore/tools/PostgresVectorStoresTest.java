package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under Postgres persistence the vector stores keep their registry and member
 * lists in the runtime's database, and the embedding index there too when it
 * has pgvector — without a URL of their own. A database without pgvector (a
 * plain {@code postgres} image) keeps the index in files, and everything still
 * works.
 */
class PostgresVectorStoresTest {

    private static final Namespace NS = new Namespace("pgvs-choice");

    @TempDir
    Path dir;

    @Test
    void withPgvector_theIndexLivesInTheRuntimesDatabase() {
        DataSource db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();

        VectorStore store = stores.open(NS, "kb", VectorStores.DEFAULT_TEMPLATE, VectorStoreInstance.Scope.GLOBAL, null);
        store.put(store.documentRef("doc1"), null, "1",
                List.of(new VectorStore.TextChunk("podman is a container engine", Map.of())));

        Sql sql = Sql.of(db);
        assertThat(sql.scalar("SELECT count(*) FROM mc_embedding WHERE namespace = ?", Long.class, NS.value()))
                .isEqualTo(1L);
        assertThat(sql.scalar("SELECT count(*) FROM mc_vector_store_member WHERE namespace = ? AND store_id = 'kb'",
                Long.class, NS.value())).isEqualTo(1L);
        assertThat(sql.scalar("SELECT count(*) FROM mc_vector_store_instance WHERE namespace = ? AND name = 'kb'",
                Long.class, NS.value())).isEqualTo(1L);
        assertThat(store.search("container", 1)).extracting(h -> h.ref().id()).containsExactly("doc1");
        assertThat(Files.exists(dir.resolve(NS.value()))).as("nothing of the stores is kept in files").isFalse();
        assertThat(stores.indexLocation(NS)).isEqualTo("Postgres (pgvector), the application's database — table mc_embedding");
    }

    @Test
    void withoutPgvector_theIndexStaysInFiles_andTheRegistryInTheDatabase() {
        DataSource db = TestDb.withoutPgvector();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();

        VectorStore store = stores.open(NS, "kb", VectorStores.DEFAULT_TEMPLATE, VectorStoreInstance.Scope.GLOBAL, null);
        store.put(store.documentRef("doc1"), null, "1",
                List.of(new VectorStore.TextChunk("podman is a container engine", Map.of())));

        assertThat(store.search("container", 1)).hasSize(1);
        assertThat(dir.resolve(NS.value()).resolve("embeddings/entries")).isDirectory();
        assertThat(dir.resolve(NS.value()).resolve("vector-stores/instances")).doesNotExist();
        assertThat(Sql.of(db).scalar("SELECT count(*) FROM mc_vector_store_instance WHERE namespace = ? AND name = 'kb'",
                Long.class, NS.value())).isEqualTo(1L);
        assertThat(stores.registry(NS).members("kb")).containsExactly(store.documentRef("doc1"));
        assertThat(stores.indexLocation(NS)).startsWith("Files in ").endsWith("pgvs-choice/embeddings — the application's Postgres has no pgvector extension");
    }

    @Test
    void theHostCanKeepTheIndexInFiles() {
        DataSource db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir,
                Map.of("vectorStoreBackend", "file"))).orElseThrow();

        VectorStore store = stores.open(NS, "kb", VectorStores.DEFAULT_TEMPLATE, VectorStoreInstance.Scope.GLOBAL, null);
        store.put(EntityRef.of(ai.mindconnect.vectorstore.embedding.EntityType.FILE, EntityRef.FILE_STORE, "file-1"),
                null, "1", List.of(new VectorStore.TextChunk("podman is a container engine", Map.of())));

        assertThat(dir.resolve(NS.value()).resolve("embeddings/entries")).isDirectory();
        assertThat(Sql.of(db).scalar("SELECT count(*) FROM mc_embedding WHERE namespace = ?", Long.class, NS.value()))
                .isZero();
        assertThat(stores.indexLocation(NS)).endsWith(" — mindconnect.vector-store.backend is 'file'");
    }
}
