package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.fileindex.FileVectorIndex;
import ai.mindconnect.vectorstore.fileindex.FileVectorIndex.FileHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real pgvector Postgres, skipped when none
 * answers — see {@link PgVectorStoreTest} for the local setup. Every test
 * works in a namespace of its own and removes its rows afterwards.
 */
class PgFileVectorIndexTest {

    private static final String URL = System.getenv().getOrDefault(
            "MC_PGVECTOR_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_PGVECTOR_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_PGVECTOR_TEST_PASSWORD", "test");

    private static final String MODEL = "nomic";
    private static final FileId A = FileId.of("file-a");
    private static final FileId B = FileId.of("file-b");

    private PGSimpleDataSource dataSource;
    private Namespace namespace;
    private FileVectorIndex index;

    private static boolean reachable() {
        try (var c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(), "no pgvector database reachable — skipping");
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        namespace = new Namespace("it-" + UUID.randomUUID().toString().substring(0, 8));
        index = new PgFileVectorIndex(dataSource, namespace);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (var c = dataSource.getConnection();
             var s = c.prepareStatement("DELETE FROM " + PgFileVectorIndex.TABLE + " WHERE namespace LIKE ?")) {
            s.setString(1, namespace.value() + "%");
            s.executeUpdate();
        }
    }

    /**
     * Pools and sessions are just file lists. Three pools with five files
     * each, one session with two files, real-sized embeddings, and a large
     * file whose chunks all lie closer to the query than anything else: a
     * search over one file list returns only chunks of those files, a full
     * topK of them, in exactly the order a brute-force ranking gives.
     */
    @Test
    void eachFileListIsSearchedInIsolationInTheSharedTable() {
        int dimension = 1536;
        Random random = new Random(42);
        float[] query = randomVector(random, dimension);

        FileId noise = FileId.of("noise");
        List<VectorChunk> near = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            near.add(new VectorChunk("n" + i, noise.value(), i, "noise " + i, Map.of(), nudge(query, random, 0.05f)));
        }
        index.replaceFile(noise, MODEL, near);

        Map<FileId, List<VectorChunk>> allChunks = new LinkedHashMap<>();
        Map<String, Set<FileId>> fileLists = new LinkedHashMap<>();
        for (String pool : List.of("handbuch", "vertraege", "support")) {
            Set<FileId> files = new LinkedHashSet<>();
            for (int f = 0; f < 5; f++) {
                FileId file = FileId.of(pool + "-" + f);
                List<VectorChunk> chunks = new ArrayList<>();
                for (int ordinal = 0; ordinal < 20; ordinal++) {
                    chunks.add(new VectorChunk("c" + ordinal, file.value(), ordinal, pool + " text",
                            Map.of(), randomVector(random, dimension)));
                }
                index.replaceFile(file, MODEL, chunks);
                allChunks.put(file, chunks);
                files.add(file);
            }
            fileLists.put(pool, files);
        }
        // A session sharing one file with a pool — indexed once, found from both.
        fileLists.put("session", Set.of(FileId.of("vertraege-0"), FileId.of("support-3")));

        for (var list : fileLists.entrySet()) {
            List<FileHit> hits = index.search(list.getValue(), MODEL, query, 10);

            List<String> expected = list.getValue().stream()
                    .flatMap(file -> allChunks.get(file).stream())
                    .sorted(Comparator.comparingDouble((VectorChunk c) -> cosine(query, c.embedding())).reversed())
                    .limit(10).map(c -> c.fileId() + "/" + c.id()).toList();
            assertThat(hits).as(list.getKey()).hasSize(10);
            assertThat(hits).as(list.getKey()).allSatisfy(h -> assertThat(list.getValue()).contains(h.file()));
            assertThat(hits).as(list.getKey()).extracting(h -> h.file() + "/" + h.chunk().id())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void noFilesNoHits() {
        index.replaceFile(A, MODEL, List.of(chunk(A, "a0", 1f, 0f, 0f)));

        assertThat(index.search(Set.of(), MODEL, new float[]{1f, 0f, 0f}, 10)).isEmpty();
    }

    @Test
    void modelsAreKeptApartAndReplacedOneAtATime() {
        index.replaceFile(A, "nomic", List.of(chunk(A, "a0", 1f, 0f, 0f), chunk(A, "a1", 0f, 1f, 0f)));
        index.replaceFile(A, "other", List.of(chunk(A, "x0", 1f, 0f, 0f)));

        assertThat(index.search(Set.of(A), "other", new float[]{1f, 0f, 0f}, 10))
                .extracting(h -> h.chunk().id()).containsExactly("x0");

        index.replaceFile(A, "nomic", List.of(chunk(A, "a2", 0f, 0f, 1f)));
        assertThat(index.chunkCount(A, "nomic")).isEqualTo(1);
        assertThat(index.chunkCount(A, "other")).isEqualTo(1);

        index.replaceFile(A, "nomic", List.of());
        assertThat(index.isIndexed(A, "nomic")).isFalse();
        assertThat(index.isIndexed(A, "other")).isTrue();

        index.deleteFile(A);
        assertThat(index.isIndexed(A, "other")).isFalse();
    }

    @Test
    void filtersByMetadataAndSkipsOtherDimensions() {
        index.replaceFile(A, MODEL, List.of(
                new VectorChunk("de", A.value(), 0, "de", Map.of("lang", "de", "page", "3"), new float[]{1f, 0f}),
                new VectorChunk("en", A.value(), 1, "en", Map.of("lang", "en"), new float[]{1f, 0f})));
        index.replaceFile(B, MODEL, List.of(chunk(B, "b0", 1f, 0f, 0f)));

        List<FileHit> german = index.search(Set.of(A, B), MODEL, new float[]{1f, 0f}, 10, Map.of("lang", "de"));
        assertThat(german).extracting(h -> h.chunk().id()).containsExactly("de");
        assertThat(german.get(0).chunk().metadata()).containsEntry("page", "3");
        assertThat(index.search(Set.of(A, B), MODEL, new float[]{1f, 0f, 0f}, 10))
                .extracting(h -> h.chunk().id()).containsExactly("b0");
    }

    @Test
    void anotherNamespaceSeesNothing() {
        index.replaceFile(A, MODEL, List.of(chunk(A, "a0", 1f, 0f, 0f)));

        FileVectorIndex other = new PgFileVectorIndex(dataSource, new Namespace(namespace.value() + "-other"));

        assertThat(other.search(Set.of(A), MODEL, new float[]{1f, 0f, 0f}, 10)).isEmpty();
        other.deleteFile(A);
        assertThat(index.chunkCount(A, MODEL)).isEqualTo(1);
    }

    @Test
    void aFailedReplaceKeepsTheOldChunks() {
        index.replaceFile(A, MODEL, List.of(chunk(A, "a0", 1f, 0f, 0f)));

        assertThatThrownBy(() -> index.replaceFile(A, MODEL, List.of(
                chunk(A, "a1", 1f, 0f, 0f), new VectorChunk("a2", A.value(), 1, "a2", Map.of(), new float[]{1f, 0f}))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(index.search(Set.of(A), MODEL, new float[]{1f, 0f, 0f}, 10))
                .extracting(h -> h.chunk().id()).containsExactly("a0");
    }

    private static VectorChunk chunk(FileId file, String id, float x, float y, float z) {
        return new VectorChunk(id, file.value(), 0, id + " text", Map.of(), new float[]{x, y, z});
    }

    private static float[] randomVector(Random random, int dimension) {
        float[] v = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            v[i] = (float) random.nextGaussian();
        }
        return v;
    }

    private static float[] nudge(float[] base, Random random, float amount) {
        float[] v = base.clone();
        for (int i = 0; i < v.length; i++) {
            v[i] += amount * (float) random.nextGaussian();
        }
        return v;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }
}
