package ai.mindconnect.vectorstore;

import ai.mindconnect.vectorstore.memory.MemoryVectorBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The memory backend's contract: cosine ranking, per-file deletion, JSONL
 * persistence across backend instances (the "restart"), dimension checks and
 * the chunk cap that pushes big corpora towards server-side backends.
 */
class MemoryVectorStoreTest {

    @TempDir
    Path dir;

    private Map<String, String> config(String... extra) {
        Map<String, String> cfg = new java.util.HashMap<>(Map.of("dir", dir.toString()));
        for (int i = 0; i < extra.length; i += 2) cfg.put(extra[i], extra[i + 1]);
        return cfg;
    }

    private static VectorChunk chunk(String id, String fileId, float... embedding) {
        return new VectorChunk(id, fileId, 0, "text of " + id, Map.of("file", fileId), embedding);
    }

    @Test
    void searchRanksByCosineSimilarity() {
        VectorStore store = new MemoryVectorBackend().open("s1", config());
        store.upsert(List.of(
                chunk("a", "f1", 1f, 0f),
                chunk("b", "f1", 0.9f, 0.1f),
                chunk("c", "f2", 0f, 1f)));

        List<VectorStore.SearchHit> hits = store.search(new float[]{1f, 0f}, 2);

        assertThat(hits).extracting(h -> h.chunk().id()).containsExactly("a", "b");
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
        // Cosine is scale-invariant: a longer vector in the same direction wins nothing.
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void persistsAcrossBackendInstances() {
        new MemoryVectorBackend().open("s2", config())
                .upsert(List.of(chunk("a", "f1", 1f, 0f), chunk("b", "f2", 0f, 1f)));

        // Fresh backend = restart: loads lazily from the JSONL file.
        VectorStore reloaded = new MemoryVectorBackend().open("s2", config());
        assertThat(reloaded.chunkCount()).isEqualTo(2);
        assertThat(reloaded.search(new float[]{0f, 1f}, 1))
                .singleElement().satisfies(h -> assertThat(h.chunk().id()).isEqualTo("b"));
        assertThat(reloaded.search(new float[]{0f, 1f}, 1).get(0).chunk().metadata())
                .containsEntry("file", "f2");
    }

    @Test
    void deleteFileRemovesOnlyThatFilesChunks() {
        VectorStore store = new MemoryVectorBackend().open("s3", config());
        store.upsert(List.of(chunk("a", "f1", 1f, 0f), chunk("b", "f2", 0f, 1f)));

        store.deleteFile("f1");

        assertThat(store.chunkCount()).isEqualTo(1);
        assertThat(new MemoryVectorBackend().open("s3", config()).chunkCount()).isEqualTo(1);
    }

    @Test
    void upsertReplacesById() {
        VectorStore store = new MemoryVectorBackend().open("s4", config());
        store.upsert(List.of(chunk("a", "f1", 1f, 0f)));
        store.upsert(List.of(chunk("a", "f1", 0f, 1f)));

        assertThat(store.chunkCount()).isEqualTo(1);
        assertThat(store.search(new float[]{0f, 1f}, 1).get(0).score())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void rejectsDimensionMismatchAndChunkCap() {
        VectorStore store = new MemoryVectorBackend().open("s5", config("maxChunksPerStore", "2"));
        store.upsert(List.of(chunk("a", "f1", 1f, 0f)));

        assertThatThrownBy(() -> store.upsert(List.of(chunk("bad", "f1", 1f, 0f, 0f))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
        assertThatThrownBy(() -> store.upsert(List.of(
                chunk("b", "f1", 0f, 1f), chunk("c", "f1", 1f, 1f))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pgvector");
    }

    @Test
    void discoverFindsTheMemoryBackend() {
        assertThat(VectorStoreBackend.discover())
                .anySatisfy(b -> assertThat(b.type()).isEqualTo("memory"));
    }
}
