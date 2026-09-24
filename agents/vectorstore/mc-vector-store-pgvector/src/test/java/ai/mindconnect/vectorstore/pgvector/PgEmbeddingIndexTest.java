package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.vectorstore.embedding.EmbeddingChunk;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.EmbeddingHit;
import ai.mindconnect.vectorstore.embedding.EmbeddingQuery;
import ai.mindconnect.vectorstore.embedding.EntityRef;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real pgvector Postgres, skipped when none
 * answers — see {@link PgVectorStoreTest} for the local setup. Every test
 * works in a namespace of its own and removes its rows afterwards.
 */
class PgEmbeddingIndexTest {

    private static final String URL = System.getenv().getOrDefault(
            "MC_PGVECTOR_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_PGVECTOR_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_PGVECTOR_TEST_PASSWORD", "test");

    private static final String MODEL = "nomic";
    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private static final EntityRef FILE_A = EntityRef.of("file", "files", "file-a");
    private static final EntityRef FILE_B = EntityRef.of("file", "files", "file-b");
    private static final EntityRef INBOX_1 = new EntityRef("mail", "email.freemail", "INBOX", "7-1");
    private static final EntityRef INBOX_2 = new EntityRef("mail", "email.freemail", "INBOX", "7-2");
    private static final EntityRef ARCHIVE_1 = new EntityRef("mail", "email.freemail", "Archive", "9-1");

    private PGSimpleDataSource dataSource;
    private Namespace namespace;
    private EmbeddingIndex index;

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
        index = new PgEmbeddingIndex(dataSource, namespace);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (var c = dataSource.getConnection();
             var s = c.prepareStatement("DELETE FROM " + PgEmbeddingIndex.TABLE + " WHERE namespace LIKE ?")) {
            s.setString(1, namespace.value() + "%");
            s.executeUpdate();
        }
    }

    /**
     * Pools and sessions are ref lists. Three pools of five files each, a
     * session sharing a file with a pool, real-sized vectors, and one large
     * entity whose chunks all lie closer to the query than anything else: a
     * search over one list returns only chunks of those entities, a full topK
     * of them, in exactly the order a brute-force ranking gives.
     */
    @Test
    void eachRefListIsSearchedInIsolationInTheSharedTable() {
        int dimension = 1536;
        Random random = new Random(42);
        float[] query = randomVector(random, dimension);

        List<EmbeddingChunk> near = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            near.add(new EmbeddingChunk("n" + i, i, "noise " + i, Map.of(), nudge(query, random, 0.05f)));
        }
        index.replace(EntityRef.of("file", "files", "noise"), null, "1", MODEL, near);

        Map<EntityRef, List<EmbeddingChunk>> allChunks = new LinkedHashMap<>();
        Map<String, Set<EntityRef>> lists = new LinkedHashMap<>();
        for (String pool : List.of("handbuch", "vertraege", "support")) {
            Set<EntityRef> refs = new LinkedHashSet<>();
            for (int f = 0; f < 5; f++) {
                EntityRef file = EntityRef.of("file", "files", pool + "-" + f);
                List<EmbeddingChunk> chunks = new ArrayList<>();
                for (int ordinal = 0; ordinal < 20; ordinal++) {
                    chunks.add(new EmbeddingChunk("c" + ordinal, ordinal, pool + " text", Map.of(),
                            randomVector(random, dimension)));
                }
                index.replace(file, ALICE, "1", MODEL, chunks);
                allChunks.put(file, chunks);
                refs.add(file);
            }
            lists.put(pool, refs);
        }
        lists.put("session", Set.of(EntityRef.of("file", "files", "vertraege-0"), EntityRef.of("file", "files", "support-3")));

        for (var list : lists.entrySet()) {
            List<EmbeddingHit> hits = index.search(EmbeddingQuery.of(MODEL, list.getValue()), query, 10);

            record Scored(String key, double score) {}
            List<String> expected = list.getValue().stream()
                    .flatMap(ref -> allChunks.get(ref).stream()
                            .map(c -> new Scored(ref.id() + "/" + c.id(), cosine(query, c.embedding()))))
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(10).map(Scored::key).toList();
            assertThat(hits).as(list.getKey()).hasSize(10);
            assertThat(hits).as(list.getKey()).allSatisfy(h -> assertThat(list.getValue()).contains(h.ref()));
            assertThat(hits).as(list.getKey()).extracting(h -> h.ref().id() + "/" + h.chunk().id())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void searchesMailOfOneFolderOfOneOwner() {
        index.replace(INBOX_1, ALICE, "v", MODEL, List.of(chunk("c0", 1f, 0f, 0f)));
        index.replace(INBOX_2, ALICE, "v", MODEL, List.of(chunk("c0", 0.9f, 0.1f, 0f)));
        index.replace(ARCHIVE_1, ALICE, "v", MODEL, List.of(chunk("c0", 1f, 0f, 0f)));
        index.replace(new EntityRef("mail", "email.freemail", "INBOX", "7-3"), BOB, "v", MODEL,
                List.of(chunk("c0", 1f, 0f, 0f)));
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 1f, 0f, 0f)));

        List<EmbeddingHit> inbox = index.search(
                EmbeddingQuery.ofTypes(MODEL, "mail").owner(ALICE).in("email.freemail", "INBOX"), vec(1f, 0f, 0f), 10);
        List<EmbeddingHit> allMail = index.search(
                EmbeddingQuery.ofTypes(MODEL, "mail").owner(ALICE).in("email.freemail"), vec(1f, 0f, 0f), 10);
        List<EmbeddingHit> everything = index.search(
                EmbeddingQuery.ofTypes(MODEL, "mail", "file").owner(ALICE), vec(1f, 0f, 0f), 10);

        assertThat(inbox).extracting(EmbeddingHit::ref).containsExactly(INBOX_1, INBOX_2);
        assertThat(inbox).allSatisfy(h -> assertThat(h.owner()).isEqualTo(ALICE));
        assertThat(allMail).extracting(EmbeddingHit::ref).containsExactlyInAnyOrder(INBOX_1, INBOX_2, ARCHIVE_1);
        assertThat(everything).extracting(EmbeddingHit::ref).containsExactlyInAnyOrder(INBOX_1, INBOX_2, ARCHIVE_1, FILE_A);
    }

    @Test
    void ownerOrSharedAndMetadata() {
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(
                new EmbeddingChunk("de", 0, "de", Map.of("lang", "de", "page", "3"), vec(1f, 0f, 0f)),
                new EmbeddingChunk("en", 1, "en", Map.of("lang", "en"), vec(1f, 0f, 0f))));
        index.replace(FILE_B, null, "1", MODEL, List.of(
                new EmbeddingChunk("b0", 0, "b0", Map.of("lang", "de"), vec(1f, 0f, 0f))));
        index.replace(EntityRef.of("file", "files", "file-c"), BOB, "1", MODEL, List.of(chunk("c0", 1f, 0f, 0f)));

        assertThat(index.search(EmbeddingQuery.ofTypes(MODEL, "file").ownerOrShared(ALICE), vec(1f, 0f, 0f), 10))
                .extracting(h -> h.chunk().id()).containsExactlyInAnyOrder("de", "en", "b0");
        List<EmbeddingHit> german = index.search(
                EmbeddingQuery.ofTypes(MODEL, "file").ownerOrShared(ALICE).where("lang", "de"), vec(1f, 0f, 0f), 10);
        assertThat(german).extracting(h -> h.chunk().id()).containsExactlyInAnyOrder("de", "b0");
        assertThat(german).filteredOn(h -> h.ref().equals(FILE_B)).allSatisfy(h -> assertThat(h.owner()).isNull());
        assertThat(german).filteredOn(h -> h.chunk().id().equals("de"))
                .allSatisfy(h -> assertThat(h.chunk().metadata()).containsEntry("page", "3"));
    }

    @Test
    void relocateMovesEveryModelWithoutReembedding() {
        index.replace(INBOX_1, ALICE, "v1", "nomic", List.of(chunk("c0", 1f, 0f, 0f)));
        index.replace(INBOX_1, ALICE, "v1", "other", List.of(chunk("c0", 0f, 1f, 0f)));
        EntityRef moved = INBOX_1.movedTo("Archive", "9-5");
        index.replace(moved, ALICE, "stale", "nomic", List.of(chunk("old", 0f, 0f, 1f)));

        index.relocate(INBOX_1, moved);

        assertThat(index.chunkCount(INBOX_1, "nomic")).isZero();
        assertThat(index.indexedVersion(moved, "nomic")).contains("v1");
        assertThat(index.chunkCount(moved, "other")).isEqualTo(1);
        assertThat(index.search(EmbeddingQuery.ofTypes(MODEL, "mail").owner(ALICE).in("email.freemail", "Archive"),
                vec(1f, 0f, 0f), 10)).extracting(h -> h.ref() + "/" + h.chunk().id()).containsExactly(moved + "/c0");
        assertThatThrownBy(() -> index.relocate(moved, EntityRef.of("file", "files", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionsModelsAndReplacement() {
        index.replace(FILE_A, ALICE, "v1", "nomic", List.of(chunk("a0", 1f, 0f, 0f), chunk("a1", 0f, 1f, 0f)));
        index.replace(FILE_A, ALICE, "v1", "other", List.of(chunk("x0", 1f, 0f, 0f)));
        index.replace(FILE_A, ALICE, "v2", "nomic", List.of(chunk("a2", 0f, 0f, 1f)));

        assertThat(index.indexedVersion(FILE_A, "nomic")).contains("v2");
        assertThat(index.indexedVersion(FILE_A, "other")).contains("v1");
        assertThat(index.chunkCount(FILE_A, "nomic")).isEqualTo(1);
        assertThat(index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)), vec(1f, 0f, 0f), 10))
                .extracting(h -> h.chunk().id()).containsExactly("a2");

        index.replace(FILE_A, ALICE, "v3", "nomic", List.of());
        assertThat(index.indexedVersion(FILE_A, "nomic")).isEmpty();
        index.delete(FILE_A);
        assertThat(index.indexedVersion(FILE_A, "other")).isEmpty();
    }

    @Test
    void concurrentReplacesOfOneEntityLeaveOneOfThem() throws Exception {
        index.replace(FILE_A, ALICE, "v0", MODEL, List.of(chunk("old", 1f, 0f, 0f)));
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int writer = w;
            done.add(pool.submit(() -> {
                start.await();
                List<EmbeddingChunk> chunks = new ArrayList<>();
                for (int i = 0; i < 5 + writer; i++) {
                    chunks.add(chunk("w" + writer + "-" + i, 1f, 0.01f * i, 0f));
                }
                index.replace(FILE_A, ALICE, "w" + writer, MODEL, chunks);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();

        String winner = index.indexedVersion(FILE_A, MODEL).orElseThrow();
        List<EmbeddingHit> all = index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)), vec(1f, 0f, 0f), 100);
        assertThat(all).extracting(h -> h.chunk().id()).allMatch(id -> id.startsWith(winner + "-"));
        assertThat(all).hasSize(5 + Integer.parseInt(winner.substring(1)));
    }

    @Test
    void anotherNamespaceSeesNothing() {
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 1f, 0f, 0f)));

        EmbeddingIndex other = new PgEmbeddingIndex(dataSource, new Namespace(namespace.value() + "-other"));

        assertThat(other.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)), vec(1f, 0f, 0f), 10)).isEmpty();
        assertThat(other.search(EmbeddingQuery.ofTypes(MODEL, "file").anyOwner(), vec(1f, 0f, 0f), 10)).isEmpty();
        other.delete(FILE_A);
        assertThat(index.chunkCount(FILE_A, MODEL)).isEqualTo(1);
    }

    @Test
    void rejectedInputChangesNothing() {
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 1f, 0f, 0f)));

        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, "2", MODEL, List.of(
                chunk("a1", 1f, 0f, 0f), new EmbeddingChunk("a2", 1, "a2", Map.of(), vec(1f, 0f)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, "2", MODEL, List.of(chunk("z", 0f, 0f, 0f))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)), vec(0f, 0f, 0f), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(index.indexedVersion(FILE_A, MODEL)).contains("1");
    }

    @Test
    void relocatingSomethingNeverIndexedLeavesTheTargetAlone() {
        EntityRef moved = INBOX_1.movedTo("Archive", "9-5");
        index.replace(moved, ALICE, "v1", MODEL, List.of(chunk("c0", 1f, 0f, 0f)));

        index.relocate(INBOX_1, moved);

        assertThat(index.indexedVersion(moved, MODEL)).contains("v1");
    }

    @Test
    void anAttributeSearchMustSayWhoseEntriesItMeans() {
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 1f, 0f, 0f)));
        index.replace(FILE_B, BOB, "1", MODEL, List.of(chunk("b0", 1f, 0f, 0f)));

        assertThatThrownBy(() -> index.search(EmbeddingQuery.ofTypes(MODEL, "file"), vec(1f, 0f, 0f), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(index.search(EmbeddingQuery.ofTypes(MODEL, "file").anyOwner(), vec(1f, 0f, 0f), 10))
                .extracting(EmbeddingHit::ref).containsExactlyInAnyOrder(FILE_A, FILE_B);
        assertThat(index.search(EmbeddingQuery.ofTypes(MODEL, "file").sharedOnly(), vec(1f, 0f, 0f), 10)).isEmpty();
        // A ref list was authorised by whoever built it: no owner needed.
        assertThat(index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_B)), vec(1f, 0f, 0f), 10)).hasSize(1);
    }

    @Test
    void rejectsChunksWithoutTextAndVectorsThatAreNotFinite() {
        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, "1", MODEL, List.of(
                new EmbeddingChunk("a0", 0, null, Map.of(), vec(1f, 0f, 0f)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", Float.NaN, 1f, 0f))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)),
                vec(Float.POSITIVE_INFINITY, 0f, 0f), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataWithoutAValueIsDropped() {
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("from", null);
        metadata.put("subject", "Rechnung");
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(new EmbeddingChunk("a0", 0, "a0", metadata, vec(1f, 0f, 0f))));

        assertThat(index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)), vec(1f, 0f, 0f), 1).get(0).chunk().metadata())
                .containsExactly(Map.entry("subject", "Rechnung"));
    }

    private static EmbeddingChunk chunk(String id, float x, float y, float z) {
        return new EmbeddingChunk(id, 0, id + " text", Map.of(), vec(x, y, z));
    }

    private static float[] vec(float... values) {
        return values;
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
