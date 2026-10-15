package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex;
import ai.mindconnect.vectorstore.embedding.FileEntryStore;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a namespace kept before 0.9 — a JSONL file per store on the memory
 * backend, a {@code vs_*} table per store on pgvector — is read into the
 * embedding index when the namespace is opened, once, and the old storage is
 * left as it was.
 */
class LegacyVectorStoresTest {

    private static final Namespace NS = new Namespace("legacy-vs");

    @TempDir
    Path dir;

    @Test
    void aMemoryStoreFileBecomesDocumentsOfItsStore() throws Exception {
        Path file = dir.resolve(NS.value()).resolve("vector-stores").resolve("session-s1.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file,
                "{\"id\":\"a.md:0\",\"fileId\":\"uploads/a.md\",\"ordinal\":0,\"text\":\"podman is a container engine\","
                        + "\"metadata\":{\"file\":\"uploads/a.md\"},\"embedding\":[1.0,0.0,0.0]}\n"
                        + "{\"id\":\"b.md:0\",\"fileId\":\"b.md\",\"ordinal\":0,\"text\":\"the finance report\","
                        + "\"metadata\":{},\"embedding\":[0.0,0.0,1.0]}\n");
        DataSource db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir,
                Map.of("vectorStoreBackend", "file"))).orElseThrow();

        VectorStore store = stores.store(NS, "session-s1");

        assertThat(store.members()).containsExactlyInAnyOrder(
                EntityRef.of(EntityType.DOCUMENT, "session-s1", "uploads/a.md"),
                EntityRef.of(EntityType.DOCUMENT, "session-s1", "b.md"));
        assertThat(store.search("container", 1)).extracting(h -> h.ref().id()).containsExactly("uploads/a.md");
        assertThat(stores.registry(NS).instance("session-s1").orElseThrow().scope())
                .as("a session- store is a chat's").isEqualTo(VectorStoreInstance.Scope.SESSION);
        assertThat(file).exists();

        // A second runtime of the same process finds the work done.
        VectorStores again = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir,
                Map.of("vectorStoreBackend", "file"))).orElseThrow();
        assertThat(again.store(NS, "session-s1").members()).hasSize(2);
    }

    @Test
    void aPgvectorTableBecomesDocumentsOfItsStore() {
        DataSource db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        Sql sql = Sql.of(db);
        sql.execute("CREATE TABLE vs_legacy_vs__kb (chunk_id text PRIMARY KEY, file_id text NOT NULL, ordinal int NOT NULL,"
                + " content text NOT NULL, metadata jsonb NOT NULL DEFAULT '{}', embedding vector(3) NOT NULL)");
        sql.update("INSERT INTO vs_legacy_vs__kb VALUES ('doc1:0', 'doc1', 0, 'podman is a container engine',"
                + " '{\"title\":\"Intro\"}', '[1,0,0]')");
        VectorStores stores = VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();

        VectorStore store = stores.store(NS, "kb");

        assertThat(store.members()).containsExactly(EntityRef.of(EntityType.DOCUMENT, "kb", "doc1"));
        assertThat(store.search("container", 1).get(0).chunk().metadata()).containsEntry("title", "Intro");
        assertThat(sql.scalar("SELECT count(*) FROM vs_legacy_vs__kb", Long.class)).isEqualTo(1L);
    }

    private static final String TWO_DOCUMENTS =
            "{\"id\":\"a:0\",\"fileId\":\"a.md\",\"ordinal\":0,\"text\":\"podman is a container engine\","
                    + "\"metadata\":{},\"embedding\":[1.0,0.0,0.0]}\n"
                    + "{\"id\":\"b:0\",\"fileId\":\"b.md\",\"ordinal\":0,\"text\":\"the finance report\","
                    + "\"metadata\":{},\"embedding\":[0.0,0.0,1.0]}\n";

    private final VectorStoreTemplate template = new VectorStoreTemplate("default", "embeddings", null, Map.of());

    private VectorStoreRegistry legacyFile(String store, String lines) throws Exception {
        Path file = dir.resolve(NS.value()).resolve("vector-stores").resolve(store + ".jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, lines);
        return new FileVectorStoreRegistry(dir.resolve(NS.value()).resolve("vector-stores"));
    }

    @Test
    void anImportThatBreaksOffListsNothing_andTheNextStartImportsTheWholeStore() throws Exception {
        VectorStoreRegistry registry = legacyFile("kb", TWO_DOCUMENTS);
        EmbeddingIndex index = FileEntryStore.index(dir.resolve("embeddings"));
        AtomicInteger writes = new AtomicInteger();
        EmbeddingIndex breaksOnTheSecond = (EmbeddingIndex) java.lang.reflect.Proxy.newProxyInstance(
                EmbeddingIndex.class.getClassLoader(), new Class<?>[]{EmbeddingIndex.class}, (proxy, method, args) -> {
                    if (method.getName().equals("replace") && writes.incrementAndGet() == 2) {
                        throw new IllegalStateException("connection lost");
                    }
                    try {
                        return method.invoke(index, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        LegacyVectorStores legacy = new LegacyVectorStores(dir, null);

        legacy.importInto(NS, registry, i -> breaksOnTheSecond, template, i -> "fake-model");
        assertThat(registry.members("kb")).as("half a store is not listed").isEmpty();

        legacy.importInto(NS, registry, i -> index, template, i -> "fake-model");
        assertThat(registry.members("kb")).containsExactlyInAnyOrder(
                EntityRef.of(EntityType.DOCUMENT, "kb", "a.md"), EntityRef.of(EntityType.DOCUMENT, "kb", "b.md"));
    }

    @Test
    void aDocumentTheIndexRefusesIsSkipped_theRestIsImported() throws Exception {
        VectorStoreRegistry registry = legacyFile("kb", TWO_DOCUMENTS
                + "{\"id\":\"z:0\",\"fileId\":\"zero.md\",\"ordinal\":0,\"text\":\"nothing\","
                + "\"metadata\":{},\"embedding\":[0.0,0.0,0.0]}\n"
                + "{\"id\":\"e:0\",\"fileId\":\"empty.md\",\"ordinal\":0,\"text\":\"\","
                + "\"metadata\":{},\"embedding\":[1.0,0.0,0.0]}\n");
        EmbeddingIndex index = FileEntryStore.index(dir.resolve("embeddings"));

        new LegacyVectorStores(dir, null).importInto(NS, registry, i -> index, template, i -> "fake-model");

        assertThat(registry.members("kb")).extracting(EntityRef::id).containsExactlyInAnyOrder("a.md", "b.md");
    }
}
