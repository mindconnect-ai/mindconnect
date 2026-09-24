package ai.mindconnect.vectorstore.memory;

import ai.mindconnect.vectorstore.VectorChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store file is JSON: written one chunk per line, but read as a sequence of
 * objects, so the whitespace in it does not matter.
 */
class MemoryVectorStoreFileFormatTest {

    @TempDir
    Path dir;

    @Test
    void aPrettyPrintedFileLoads() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.writeString(file, """
                {
                  "id" : "a:0",
                  "fileId" : "a",
                  "ordinal" : 0,
                  "text" : "alpha",
                  "metadata" : { "file" : "a" },
                  "embedding" : [ 1.0, 0.0 ]
                }
                {
                  "id" : "b:0",
                  "fileId" : "b",
                  "ordinal" : 0,
                  "text" : "beta",
                  "metadata" : { },
                  "embedding" : [ 0.0, 1.0 ]
                }
                """);

        MemoryVectorStore store = new MemoryVectorStore("s", file, 1000);

        assertThat(store.chunkCount()).isEqualTo(2);
        assertThat(store.search(new float[]{0f, 1f}, 1).get(0).chunk().text()).isEqualTo("beta");
    }

    @Test
    void theNextWriteIsOneChunkPerLineAgain() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.writeString(file, """
                {
                  "id" : "a:0", "fileId" : "a", "ordinal" : 0, "text" : "alpha",
                  "metadata" : { }, "embedding" : [ 1.0, 0.0 ]
                }
                """);

        new MemoryVectorStore("s", file, 1000).upsert(List.of(
                new VectorChunk("b:0", "b", 0, "beta", Map.of(), new float[]{0f, 1f})));

        assertThat(Files.readAllLines(file)).hasSize(2)
                .allSatisfy(line -> assertThat(line).startsWith("{").endsWith("}"));
    }

    @Test
    void aStoreFileReadsBackAsWritten_forMovingItElsewhere() throws Exception {
        Map<String, String> config = Map.of("baseDir", dir.toString(), "namespace", "ns");
        Path file = MemoryVectorBackend.storeFile("session/1", config);
        assertThat(file).isEqualTo(dir.resolve("ns/vector-stores/session-1.jsonl").toAbsolutePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id":"a:0","fileId":"a","ordinal":0,"text":"alpha","metadata":{},"embedding":[3.0,4.0]}
                """);

        List<VectorChunk> chunks = MemoryVectorBackend.readChunks(file);

        assertThat(chunks).singleElement().satisfies(c ->
                assertThat(c.embedding()).as("not normalised").containsExactly(3f, 4f));
        assertThat(MemoryVectorBackend.readChunks(dir.resolve("missing.jsonl"))).isEmpty();
        assertThat(new MemoryVectorStore("s", file, 1000).dimension()).hasValue(2);
        assertThat(new MemoryVectorStore("t", dir.resolve("t.jsonl"), 1000).dimension()).isEmpty();
    }

    @Test
    void aBrokenFileIsAnErrorNotAnEmptyStore() throws Exception {
        Path file = dir.resolve("s.jsonl");
        Files.writeString(file, "{ \"id\" : \"a:0\", \"fileId\" : ");

        MemoryVectorStore store = new MemoryVectorStore("s", file, 1000);

        assertThatThrownBy(store::chunkCount)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(file.toString());
    }
}
