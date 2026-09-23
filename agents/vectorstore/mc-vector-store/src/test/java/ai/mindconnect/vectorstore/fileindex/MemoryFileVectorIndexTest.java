package ai.mindconnect.vectorstore.fileindex;

import ai.mindconnect.filestore.FileId;
import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.fileindex.FileVectorIndex.FileHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryFileVectorIndexTest {

    private static final String MODEL = "nomic";
    private static final FileId A = FileId.of("file-a");
    private static final FileId B = FileId.of("file-b");
    private static final FileId BIG = FileId.of("file-big");

    private final FileVectorIndex index = new MemoryFileVectorIndex();

    @Test
    void searchesOnlyTheGivenFilesAndStillFillsTopK() {
        List<VectorChunk> near = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            near.add(new VectorChunk("n" + i, BIG.value(), i, "near " + i, Map.of(), new float[]{1f, 0.001f * i, 0f}));
        }
        index.replaceFile(BIG, MODEL, near);
        index.replaceFile(A, MODEL, List.of(chunk(A, "a0", 0.8f, 0.2f, 0f), chunk(A, "a1", 0.5f, 0.5f, 0f)));
        index.replaceFile(B, MODEL, List.of(chunk(B, "b0", 0f, 0f, 1f)));

        List<FileHit> hits = index.search(Set.of(A, B), MODEL, new float[]{1f, 0f, 0f}, 3);

        assertThat(hits).extracting(h -> h.chunk().id()).containsExactly("a0", "a1", "b0");
        assertThat(hits).extracting(FileHit::file).containsExactly(A, A, B);
        assertThat(hits).allSatisfy(h -> assertThat(h.chunk().embedding()).isEmpty());
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
                new VectorChunk("de", A.value(), 0, "de", Map.of("lang", "de"), new float[]{1f, 0f}),
                new VectorChunk("en", A.value(), 1, "en", Map.of("lang", "en"), new float[]{1f, 0f})));
        index.replaceFile(B, MODEL, List.of(chunk(B, "b0", 1f, 0f, 0f)));

        assertThat(index.search(Set.of(A, B), MODEL, new float[]{1f, 0f}, 10, Map.of("lang", "de")))
                .extracting(h -> h.chunk().id()).containsExactly("de");
        assertThat(index.search(Set.of(A, B), MODEL, new float[]{1f, 0f, 0f}, 10))
                .extracting(h -> h.chunk().id()).containsExactly("b0");
    }

    @Test
    void rejectsForeignChunksDuplicatesMixedDimensionsAndBlankModels() {
        assertThatThrownBy(() -> index.replaceFile(A, MODEL, List.of(chunk(B, "b0", 1f, 0f, 0f))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replaceFile(A, MODEL, List.of(chunk(A, "a0", 1f, 0f, 0f), chunk(A, "a0", 0f, 1f, 0f))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replaceFile(A, MODEL, List.of(
                chunk(A, "a0", 1f, 0f, 0f), new VectorChunk("a1", A.value(), 1, "a1", Map.of(), new float[]{1f, 0f}))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replaceFile(A, " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static VectorChunk chunk(FileId file, String id, float x, float y, float z) {
        return new VectorChunk(id, file.value(), 0, id + " text", Map.of(), new float[]{x, y, z});
    }
}
