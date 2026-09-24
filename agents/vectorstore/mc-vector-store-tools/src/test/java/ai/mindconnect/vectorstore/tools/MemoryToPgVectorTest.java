package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.memory.MemoryVectorBackend;
import ai.mindconnect.vectorstore.pgvector.PgVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A namespace that kept its stores in files — the registry under
 * {@code vector-stores/templates|instances}, the vectors of the memory backend
 * as {@code vector-stores/<store>.jsonl} — moves into Postgres on first use
 * once the runtime's database has pgvector: the records into the registry
 * tables, the chunks into the stores' pgvector tables, the records then naming
 * pgvector. What cannot move stays on memory and keeps working. The files stay.
 */
class MemoryToPgVectorTest {

    private static final Namespace NS = new Namespace("pgvs-move");

    @TempDir
    Path dir;

    private DataSource db;
    private Path storesDir;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.requirePostgres();
        TestDb.forget(db, NS);
        storesDir = dir.resolve(NS.value()).resolve("vector-stores");
        Map<String, String> memory = Map.of("baseDir", dir.toString(), "namespace", NS.value());

        // What 0.8.x wrote under file persistence — and under Postgres persistence as well.
        FileVectorStoreRegistry files = new FileVectorStoreRegistry(storesDir);
        VectorStoreTemplate chatUploads = files.saveTemplate(new VectorStoreTemplate("chat-uploads", "memory",
                Map.of(), "embeddings", "file-ingestion", Map.of()));
        files.registerInstance(VectorStoreInstance.fromTemplate("session-a", chatUploads,
                VectorStoreInstance.Scope.SESSION, "a", "alice"));
        files.registerInstance(VectorStoreInstance.fromTemplate("session-c", chatUploads,
                VectorStoreInstance.Scope.SESSION, "c", "carol"));
        files.registerInstance(VectorStoreInstance.fromTemplate("session-empty", chatUploads,
                VectorStoreInstance.Scope.SESSION, "empty", "erin"));
        MemoryVectorBackend backend = new MemoryVectorBackend();
        backend.open("session-a", memory).upsert(List.of(
                chunk("f1:0", "f1", "podman is a container engine", 1f, 0f, 0f),
                chunk("f1:1", "f1", "the finance report", 0f, 1f, 0f)));
        // Nobody registered this one: an upload store written before the registry, say.
        backend.open("session-b", memory).upsert(List.of(chunk("f2:0", "f2", "container images", 1f, 0f, 0f)));
        // Two dimensions where its pgvector table holds three.
        backend.open("session-c", memory).upsert(List.of(chunk("f3:0", "f3", "short vectors", 1f, 0f)));
        PgVectorStore existing = new PgVectorStore(db, NS.value(), "session-c");
        existing.upsert(List.of(chunk("x", "x", "x", 1f, 0f, 0f)));
        existing.deleteFile("x");
        // Chunks of two dimensions in one file: nothing to import.
        Files.writeString(storesDir.resolve("session-d.jsonl"), """
                {"id":"a","fileId":"f","ordinal":0,"text":"a","metadata":{},"embedding":[1.0,0.0,0.0]}
                {"id":"b","fileId":"f","ordinal":1,"text":"b","metadata":{},"embedding":[1.0,0.0]}
                """);
    }

    @Test
    void theStoresMoveToPgvector_whatCannotMoveStaysOnMemory_andTheFilesStay() {
        VectorStores stores = stores();
        VectorStoreRegistry registry = stores.registry(NS);

        assertThat(registry.template("chat-uploads").orElseThrow().backend()).as("new chat stores follow")
                .isEqualTo("pgvector");

        VectorStoreInstance a = registry.instance("session-a").orElseThrow();
        assertThat(a.backend()).isEqualTo("pgvector");
        assertThat(a.owner()).isEqualTo("alice");
        assertThat(new PgVectorStore(db, NS.value(), "session-a").chunkCount()).isEqualTo(2);
        assertThat(stores.openWith(NS, a).search(new float[]{1f, 0f, 0f}, 1).get(0).chunk().text())
                .contains("podman");

        VectorStoreInstance b = registry.instance("session-b").orElseThrow();
        assertThat(b.backend()).isEqualTo("pgvector");
        assertThat(b.scope()).isEqualTo(VectorStoreInstance.Scope.SESSION);
        assertThat(b.scopeRef()).isEqualTo("b");
        assertThat(stores.openWith(NS, b).chunkCount()).isEqualTo(1);

        VectorStoreInstance c = registry.instance("session-c").orElseThrow();
        assertThat(c.backend()).as("its table holds another dimension").isEqualTo("memory");
        assertThat(new PgVectorStore(db, NS.value(), "session-c").chunkCount()).isZero();
        assertThat(stores.openWith(NS, c).chunkCount()).as("still served from its file").isEqualTo(1);

        assertThat(registry.instance("session-empty").orElseThrow().backend()).isEqualTo("pgvector");

        assertThat(registry.instance("session-d")).isEmpty();
        assertThat(new PgVectorStore(db, NS.value(), "session-d").chunkCount()).isZero();

        assertThat(storesDir.resolve("session-a.jsonl")).exists();
        assertThat(storesDir.resolve("session-b.jsonl")).exists();
        assertThat(storesDir.resolve("instances/session-a.json")).exists();
    }

    @Test
    void theNextStartImportsNothingAgain_notEvenIntoAStoreEmptiedSince() {
        VectorStores first = stores();
        VectorStoreRegistry registry = first.registry(NS);
        first.openWith(NS, registry.instance("session-a").orElseThrow()).deleteFile("f1");
        first.openWith(NS, registry.instance("session-b").orElseThrow()).upsert(List.of(
                chunk("f9:0", "f9", "added after the move", 0f, 0f, 1f)));

        VectorStores next = stores();
        VectorStoreRegistry again = next.registry(NS);

        assertThat(next.openWith(NS, again.instance("session-a").orElseThrow()).chunkCount())
                .as("emptied on purpose, and it stays so").isZero();
        assertThat(next.openWith(NS, again.instance("session-b").orElseThrow()).chunkCount()).isEqualTo(2);
        assertThat(again.instance("session-c").orElseThrow().backend()).isEqualTo("memory");
    }

    private VectorStores stores() {
        return VectorStores.fromEnvironment(TestDb.postgresEnvironment(db, dir, Map.of())).orElseThrow();
    }

    private static VectorChunk chunk(String id, String fileId, String text, float... embedding) {
        return new VectorChunk(id, fileId, 0, text, Map.of("file", fileId), embedding);
    }
}
