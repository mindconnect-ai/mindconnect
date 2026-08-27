package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real pgvector Postgres. Skipped (via assumption)
 * when no database answers — CI runs without one; locally run e.g.
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie}
 * and set {@code MC_PGVECTOR_TEST_URL} if not using the default below.
 */
class PgVectorStoreTest {

    private static final String URL = System.getenv().getOrDefault(
            "MC_PGVECTOR_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_PGVECTOR_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_PGVECTOR_TEST_PASSWORD", "test");

    private static boolean reachable() {
        try (var c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void upsertSearchDeleteRoundTrip() {
        assumeTrue(reachable(), "no pgvector database reachable — skipping");

        String storeId = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        VectorStore store = new PgVectorBackend().open(storeId, Map.of(
                "url", URL, "user", USER, "password", PASSWORD));

        store.upsert(List.of(
                new VectorChunk("a", "f1", 0, "alpha text", Map.of("k", "v"), new float[]{1f, 0f, 0f}),
                new VectorChunk("b", "f1", 1, "beta text", Map.of(), new float[]{0.9f, 0.1f, 0f}),
                new VectorChunk("c", "f2", 0, "gamma text", Map.of(), new float[]{0f, 0f, 1f})));

        assertThat(store.chunkCount()).isEqualTo(3);
        List<VectorStore.SearchHit> hits = store.search(new float[]{1f, 0f, 0f}, 2);
        assertThat(hits).extracting(h -> h.chunk().id()).containsExactly("a", "b");
        assertThat(hits.get(0).score()).isGreaterThan(0.99);
        assertThat(hits.get(0).chunk().metadata()).containsEntry("k", "v");

        // Upsert replaces by id.
        store.upsert(List.of(new VectorChunk("a", "f1", 0, "alpha v2", Map.of(), new float[]{0f, 1f, 0f})));
        assertThat(store.chunkCount()).isEqualTo(3);
        assertThat(store.search(new float[]{0f, 1f, 0f}, 1).get(0).chunk().text()).isEqualTo("alpha v2");

        store.deleteFile("f1");
        assertThat(store.chunkCount()).isEqualTo(1);
    }

    @Test
    void directDataSourceConstructor() {
        assumeTrue(reachable(), "no pgvector database reachable — skipping");

        // The Spring-host path: hand the store an existing DataSource (in a
        // Boot app the auto-configured Hikari pool; here the plain PG one).
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);

        String storeId = "it_ds_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        VectorStore store = new PgVectorStore(ds, storeId);

        store.upsert(List.of(
                new VectorChunk("x", "f1", 0, "x text", Map.of(), new float[]{1f, 0f}),
                new VectorChunk("y", "f1", 1, "y text", Map.of(), new float[]{0f, 1f})));
        assertThat(store.chunkCount()).isEqualTo(2);
        assertThat(store.search(new float[]{0f, 1f}, 1).get(0).chunk().id()).isEqualTo("y");
        assertThat(store.listFiles()).containsEntry("f1", 2L);
        store.deleteFile("f1");
        assertThat(store.chunkCount()).isZero();
    }
}
