package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The file persistence's index survives a restart: what is written is read back, moves and deletes included. */
class FileEntryStoreTest {

    private static final String MODEL = "nomic";
    private static final UserId ALICE = UserId.of("alice");
    private static final EntityRef INBOX = new EntityRef(EntityType.MAIL, "email.freemail", "INBOX", "7-1");
    private static final EntityRef FILE = EntityRef.of(EntityType.FILE, EntityRef.FILE_STORE, "file/with:odd chars");

    @TempDir
    Path dir;

    @Test
    void whatIsWrittenIsThereAfterARestart() {
        EmbeddingIndex index = FileEntryStore.index(dir);
        index.declareField(new MetadataField(EntityType.MAIL, "received_at", MetadataField.Kind.TIMESTAMP));
        index.replace(INBOX, ALICE, "v1", MODEL, List.of(new EmbeddingChunk("c0", 0, "invoice",
                Map.of("received_at", "2026-09-01T08:00:00Z"), new float[]{1f, 0f})));
        index.replace(FILE, null, "1", MODEL, List.of(new EmbeddingChunk("c0", 0, "podman", Map.of(), new float[]{0f, 1f})));
        EntityRef archived = INBOX.movedTo("Archive", "9-4");
        index.relocate(INBOX, archived);
        index.replace(FILE, null, "2", "other", List.of(new EmbeddingChunk("c0", 0, "x", Map.of(), new float[]{1f, 1f})));
        index.replace(FILE, null, "2", "other", List.of());

        FileEntryStore.forget(dir);
        EmbeddingIndex restarted = FileEntryStore.index(dir);

        assertThat(restarted).isNotSameAs(index);
        assertThat(restarted.indexedVersion(INBOX, MODEL)).isEmpty();
        assertThat(restarted.indexedVersion(archived, MODEL)).contains("v1");
        assertThat(restarted.indexedVersion(FILE, "other")).isEmpty();
        assertThat(restarted.fields()).containsExactly(
                new MetadataField(EntityType.MAIL, "received_at", MetadataField.Kind.TIMESTAMP));
        assertThat(restarted.search(EmbeddingQuery.ofTypes(MODEL, EntityType.MAIL).owner(ALICE)
                .atLeast("received_at", "2026-08-01T00:00:00Z"), new float[]{1f, 0f}, 5))
                .extracting(h -> h.ref()).containsExactly(archived);
        assertThat(restarted.search(EmbeddingQuery.of(MODEL, Set.of(FILE)), new float[]{0f, 1f}, 5))
                .extracting(h -> h.chunk().text()).containsExactly("podman");

        restarted.delete(FILE);
        FileEntryStore.forget(dir);
        assertThat(FileEntryStore.index(dir).indexedVersion(FILE, MODEL)).isEmpty();
    }

    @Test
    void theSameDirectoryIsOneIndexInTheJvm() throws Exception {
        assertThat(FileEntryStore.index(dir)).isSameAs(FileEntryStore.index(dir.resolve(".").normalize()));
        FileEntryStore.index(dir).replace(FILE, null, "1", MODEL,
                List.of(new EmbeddingChunk("c0", 0, "podman", Map.of(), new float[]{0f, 1f})));
        try (var files = Files.walk(dir.resolve("entries"))) {
            assertThat(files.filter(f -> f.toString().endsWith(".json")).count()).isEqualTo(1);
        }
    }
}
