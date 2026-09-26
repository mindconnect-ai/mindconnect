package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The vector tools on the pgvector backend, end to end: the template built
 * from the {@code mindconnect.vector-store.*} properties opens a store in
 * Postgres, an upsert lands as rows in that namespace's table, a search is a
 * cosine query against pgvector, a delete empties it. Everything above the
 * backend is what the memory-backed tests cover; this one is about the wiring
 * to a real database. Skipped without one (see {@code PgVectorStoreTest}).
 */
class VectorToolsPgVectorTest {

    private static final String URL = System.getenv().getOrDefault(
            "MC_PGVECTOR_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_PGVECTOR_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_PGVECTOR_TEST_PASSWORD", "test");

    private static final Namespace NS = new Namespace("pgtools");
    /** Where every store's chunks live: the embedding index, one table for all namespaces. */
    private static final String TABLE = "mc_embedding";

    private static final LlmEmbeddings FAKE_EMBEDDINGS = (config, texts) -> texts.stream()
            .map(t -> {
                if (t.contains("container")) return new float[]{1f, 0f, 0f};
                if (t.contains("finance")) return new float[]{0f, 1f, 0f};
                return new float[]{0f, 0f, 1f};
            }).toList();

    private static final LlmConfigRepository FAKE_CONFIGS = new LlmConfigRepository() {
        @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.empty(); }
        @Override public Optional<LlmConfig> findByName(String name) {
            return "embeddings".equals(name)
                    ? Optional.of(LlmConfig.lmStudio("embeddings", "fake-model", "http://unused"))
                    : Optional.empty();
        }
        @Override public List<LlmConfig> findAll() { return List.of(); }
        @Override public void save(LlmConfig config) { }
        @Override public void deleteById(LlmConfigId id) { }
    };

    @TempDir
    Path dir;

    private ToolEnvironment env;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(reachable(), "no pgvector Postgres reachable — skipping");
        dropTable();
        env = new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) FAKE_EMBEDDINGS);
                if (type == LlmConfigRepository.class) return Optional.of((T) FAKE_CONFIGS);
                if (type == Namespace.class) return Optional.of((T) NS);
                return Optional.empty();
            }
            @Override public Optional<String> getString(String key) {
                return switch (key) {
                    case "vectorStoreBackend" -> Optional.of("pgvector");
                    case "vectorStoreUrl" -> Optional.of(URL);
                    case "vectorStoreUser" -> Optional.of(USER);
                    case "vectorStorePassword" -> Optional.of(PASSWORD);
                    case "dataBaseDir", "defaultBaseDir" -> Optional.of(dir.toString());
                    default -> Optional.empty();
                };
            }
        };
    }

    @AfterEach
    void tearDown() throws Exception {
        if (env != null) dropTable();
    }

    @Test
    void theToolsUpsertSearchAndDeleteInPostgres() throws Exception {
        Tool upsert = tool(new VectorTools.UpsertFactory());
        Tool search = tool(new VectorTools.SearchFactory());
        Tool delete = tool(new VectorTools.DeleteFileFactory());

        String stored = upsert.execute(Map.of("store", "kb", "file_id", "doc1", "chunks", List.of(
                Map.of("text", "podman is a container engine", "title", "Intro"),
                Map.of("text", "the finance report shows growth"))));
        assertThat(stored).contains("Stored 2 chunk(s)");
        assertThat(rowsInTable()).as("the chunks are rows of the embedding index, under the namespace").isEqualTo(2);

        String found = search.execute(Map.of("store", "kb", "query", "how to run a container"));
        assertThat(found).contains("podman").contains("Intro").contains("doc1");
        assertThat(found.indexOf("podman")).isLessThan(found.indexOf("finance"));

        assertThat(delete.execute(Map.of("store", "kb", "file_id", "doc1"))).contains("Removed");
        assertThat(rowsInTable()).isZero();
        assertThat(search.execute(Map.of("store", "kb", "query", "container"))).contains("No results");
    }

    private Tool tool(VectorTools.BaseFactory factory) {
        factory.bind(env);
        assertThat(factory.isAvailable()).as("the pgvector backend is discovered").isTrue();
        return factory.create(null, null);
    }

    private static boolean reachable() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    private static void dropTable() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("DELETE FROM " + TABLE + " WHERE namespace = '" + NS.value() + "'");
            s.execute("DELETE FROM mc_vector_store_member WHERE namespace = '" + NS.value() + "'");
        }
    }

    private static long rowsInTable() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + TABLE + " WHERE namespace = '" + NS.value() + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
