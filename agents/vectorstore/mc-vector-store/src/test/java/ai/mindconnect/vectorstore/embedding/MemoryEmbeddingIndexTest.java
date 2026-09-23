package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.EmbeddingHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryEmbeddingIndexTest {

    private static final String MODEL = "nomic";
    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private static final EntityRef FILE_A = EntityRef.of("file", "files", "file-a");
    private static final EntityRef FILE_B = EntityRef.of("file", "files", "file-b");
    private static final EntityRef INBOX_1 = new EntityRef("mail", "email.freemail", "INBOX", "7-1");
    private static final EntityRef INBOX_2 = new EntityRef("mail", "email.freemail", "INBOX", "7-2");
    private static final EntityRef ARCHIVE_1 = new EntityRef("mail", "email.freemail", "Archive", "9-1");

    private final EmbeddingIndex index = new MemoryEmbeddingIndex();

    @Test
    void searchesOnlyTheNamedEntitiesAndStillFillsTopK() {
        List<EmbeddingChunk> near = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            near.add(chunk("n" + i, 1f, 0.001f * i, 0f));
        }
        index.replace(EntityRef.of("file", "files", "big"), null, "1", MODEL, near);
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 0.8f, 0.2f, 0f), chunk("a1", 0.5f, 0.5f, 0f)));
        index.replace(FILE_B, ALICE, "1", MODEL, List.of(chunk("b0", 0f, 0f, 1f)));

        List<EmbeddingHit> hits = index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A, FILE_B)), vec(1f, 0f, 0f), 3);

        assertThat(hits).extracting(h -> h.chunk().id()).containsExactly("a0", "a1", "b0");
        assertThat(hits).extracting(EmbeddingHit::ref).containsExactly(FILE_A, FILE_A, FILE_B);
        assertThat(hits.get(0).score()).isCloseTo(0.970, org.assertj.core.data.Offset.offset(0.001));
        assertThat(hits).allSatisfy(h -> assertThat(h.chunk().embedding()).isEmpty());
        assertThat(index.search(EmbeddingQuery.of(MODEL, Set.of()), vec(1f, 0f, 0f), 3)).isEmpty();
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
        assertThat(allMail).extracting(EmbeddingHit::ref).containsExactlyInAnyOrder(INBOX_1, INBOX_2, ARCHIVE_1);
        assertThat(everything).extracting(EmbeddingHit::ref).containsExactlyInAnyOrder(INBOX_1, INBOX_2, ARCHIVE_1, FILE_A);
    }

    @Test
    void ownerOrSharedIncludesWhatBelongsToNobody() {
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 1f, 0f, 0f)));
        index.replace(FILE_B, null, "1", MODEL, List.of(chunk("b0", 1f, 0f, 0f)));
        index.replace(EntityRef.of("file", "files", "file-c"), BOB, "1", MODEL, List.of(chunk("c0", 1f, 0f, 0f)));

        assertThat(index.search(EmbeddingQuery.ofTypes(MODEL, "file").ownerOrShared(ALICE), vec(1f, 0f, 0f), 10))
                .extracting(EmbeddingHit::ref).containsExactlyInAnyOrder(FILE_A, FILE_B);
        assertThat(index.search(EmbeddingQuery.ofTypes(MODEL, "file").owner(ALICE), vec(1f, 0f, 0f), 10))
                .extracting(EmbeddingHit::ref).containsExactly(FILE_A);
    }

    @Test
    void relocateMovesEveryModelWithoutReembedding() {
        index.replace(INBOX_1, ALICE, "v1", "nomic", List.of(chunk("c0", 1f, 0f, 0f)));
        index.replace(INBOX_1, ALICE, "v1", "other", List.of(chunk("c0", 0f, 1f, 0f)));
        EntityRef moved = INBOX_1.movedTo("Archive", "9-5");

        index.relocate(INBOX_1, moved);

        assertThat(index.chunkCount(INBOX_1, "nomic")).isZero();
        assertThat(index.indexedVersion(moved, "nomic")).contains("v1");
        assertThat(index.chunkCount(moved, "other")).isEqualTo(1);
        assertThat(index.search(EmbeddingQuery.ofTypes(MODEL, "mail").owner(ALICE).in("email.freemail", "Archive"),
                vec(1f, 0f, 0f), 10)).extracting(EmbeddingHit::ref).containsExactly(moved);
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

        index.replace(FILE_A, ALICE, "v3", "nomic", List.of());
        assertThat(index.indexedVersion(FILE_A, "nomic")).isEmpty();
        index.delete(FILE_A);
        assertThat(index.indexedVersion(FILE_A, "other")).isEmpty();
    }

    @Test
    void keepsItsOwnCopyOfTheVectors() {
        float[] buffer = vec(1f, 0f, 0f);
        index.replace(FILE_A, ALICE, "1", MODEL, List.of(new EmbeddingChunk("a0", 0, "a0", Map.of(), buffer)));
        buffer[0] = 0f;
        buffer[1] = 1f;

        assertThat(index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)), vec(1f, 0f, 0f), 1).get(0).score())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 1f, 0f, 0f), chunk("a0", 0f, 1f, 0f))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, "1", MODEL, List.of(
                chunk("a0", 1f, 0f, 0f), new EmbeddingChunk("a1", 1, "a1", Map.of(), vec(1f, 0f)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, "1", MODEL, List.of(chunk("a0", 0f, 0f, 0f))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.replace(FILE_A, ALICE, null, MODEL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.search(EmbeddingQuery.of(MODEL, Set.of(FILE_A)), vec(0f, 0f, 0f), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmbeddingQuery.ofTypes(MODEL)).isInstanceOf(IllegalArgumentException.class);
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
}
