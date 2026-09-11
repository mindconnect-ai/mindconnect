package ai.mindconnect.vectorstore.memory;

import ai.mindconnect.vectorstore.VectorChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Ingestion and search from many virtual threads at once: every chunk lands, every search answers. */
class MemoryVectorStoreConcurrencyTest {

    @Test
    void concurrentUpsertsAndSearchesLoseNothing(@TempDir Path dir) throws Exception {
        MemoryVectorStore store = new MemoryVectorStore("docs", dir.resolve("docs.jsonl"), 10_000);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int n = i;
                futures.add(pool.submit(() -> store.upsert(List.of(chunk("file-" + n, n)))));
                futures.add(pool.submit(() -> store.search(new float[]{1f, 0f, 0f}, 3)));
            }
            for (Future<?> future : futures) future.get();
        }

        assertThat(store.chunkCount()).isEqualTo(100);
        MemoryVectorStore reloaded = new MemoryVectorStore("docs", dir.resolve("docs.jsonl"), 10_000);
        assertThat(reloaded.chunkCount()).as("every chunk made it into the file").isEqualTo(100);
    }

    private static VectorChunk chunk(String fileId, int n) {
        return new VectorChunk("chunk-" + n, fileId, 0, "text " + n, Map.of(),
                new float[]{1f, n, 0.5f});
    }
}
