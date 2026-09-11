package ai.mindconnect.agent.domain;

import ai.mindconnect.agent.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The attached-file record, and how a session written with bare names reads today. */
class AttachedFileTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void kindsFollowTheMediaTypeAndFallBackToTheExtension() {
        assertThat(new AttachedFile("f", "photo.png", "image/png", 1).isImage()).isTrue();
        assertThat(new AttachedFile("f", "photo.bin", "image/webp", 1).isImage()).isTrue();
        assertThat(AttachedFile.named("photo.JPG").isImage()).isTrue();
        assertThat(AttachedFile.named("spec.pdf").isPdf()).isTrue();
        assertThat(new AttachedFile("f", "spec", "application/pdf", 1).isPdf()).isTrue();
        assertThat(new AttachedFile("f", "notes.md", "text/markdown", 1).isImage()).isFalse();
        assertThat(new AttachedFile("f", "notes.md", "text/markdown", 1).isPdf()).isFalse();
    }

    @Test
    void aGenericContentTypeFallsBackToTheExtension() {
        // curl and some drag-and-drop sources send application/octet-stream for anything
        AttachedFile png = new AttachedFile("f", "photo.png", "application/octet-stream", 1);
        assertThat(png.isImage()).isTrue();
        assertThat(png.effectiveMediaType()).isEqualTo("image/png");
        AttachedFile pdf = new AttachedFile("f", "spec.pdf", "", 1);
        assertThat(pdf.isPdf()).isTrue();
        assertThat(pdf.effectiveMediaType()).isEqualTo("application/pdf");
        assertThat(AttachedFile.named("scan.jpeg").effectiveMediaType()).isEqualTo("image/jpeg");
        assertThat(new AttachedFile("f", "notes.md", null, 1).effectiveMediaType()).isNull();
        assertThat(new AttachedFile("f", "photo.bin", "image/webp", 1).effectiveMediaType()).isEqualTo("image/webp");
    }

    @Test
    void aWindowsJpgUploadIsSentAsImageJpeg() {
        // Windows reports .jpg as image/jpg or image/pjpeg; the providers take image/jpeg only.
        AttachedFile jpg = new AttachedFile("f", "IMG_0001.jpg", "image/jpg", 1);
        assertThat(jpg.isImage()).isTrue();
        assertThat(jpg.effectiveMediaType()).isEqualTo("image/jpeg");
        assertThat(new AttachedFile("f", "scan.jpg", "image/pjpeg", 1).effectiveMediaType()).isEqualTo("image/jpeg");
    }

    @Test
    void onlyAFileWithAnIdAndAReadableKindIsSendableAsAPart() {
        assertThat(new AttachedFile("f", "photo.png", "image/png", 1).sendableAsPart()).isTrue();
        assertThat(new AttachedFile("f", "spec.pdf", "application/pdf", 1).sendableAsPart()).isTrue();
        assertThat(new AttachedFile("f", "notes.md", "text/markdown", 1).sendableAsPart()).isFalse();
        assertThat(AttachedFile.named("photo.png").sendableAsPart()).as("legacy entry, no id").isFalse();
    }

    @Test
    void aSessionWrittenWithBareNamesStillLoads() throws Exception {
        // A session as written before the record existed: names only.
        String current = json.writeValueAsString(new AgentSession(UUID.randomUUID(), UUID.randomUUID(),
                new Namespace("local"), "u", UUID.randomUUID(), "t", SessionStatus.ACTIVE, Instant.now(),
                null, null, null, null));
        assertThat(current).contains("\"attachedFiles\":[]");
        String legacy = current.replace("\"attachedFiles\":[]", "\"attachedFiles\":[\"notes.md\",\"photo.png\"]");

        AgentSession session = json.readValue(legacy, AgentSession.class);

        assertThat(session.attachedFileNames()).containsExactly("notes.md", "photo.png");
        assertThat(session.attachedFiles()).allMatch(f -> f.id() == null);
        assertThat(session.attachedFile("photo.png")).get().extracting(AttachedFile::isImage).isEqualTo(true);
    }

    @Test
    void aSessionRoundTripsItsAttachedFiles() throws Exception {
        AgentSession session = new AgentSession(UUID.randomUUID(), UUID.randomUUID(), new Namespace("local"),
                "u", UUID.randomUUID(), "t", SessionStatus.ACTIVE, Instant.now(), null, null, null, null)
                .withAttachedFiles(List.of(new AttachedFile("f-1", "photo.png", "image/png", 240_000)));

        String written = json.writeValueAsString(session);
        AgentSession read = json.readValue(written, AgentSession.class);

        assertThat(written).contains("\"attachedFiles\":[{\"id\":\"f-1\",\"name\":\"photo.png\"");
        assertThat(read.attachedFiles()).containsExactly(new AttachedFile("f-1", "photo.png", "image/png", 240_000));
    }

    @Test
    void attachingTheSameNameAgainReplacesTheEntryInPlace() {
        AgentSession session = new AgentSession(UUID.randomUUID(), UUID.randomUUID(), new Namespace("local"),
                "u", UUID.randomUUID(), "t", SessionStatus.ACTIVE, Instant.now(), null, null, null, null)
                .withAttachedFiles(List.of(AttachedFile.named("a.md"), new AttachedFile("f-1", "photo.png", "image/png", 1)))
                .withAttachedFiles(List.of(new AttachedFile("f-2", "photo.png", "image/png", 2)));

        assertThat(session.attachedFileNames()).containsExactly("a.md", "photo.png");
        assertThat(session.attachedFile("photo.png")).get().extracting(AttachedFile::id).isEqualTo("f-2");
        assertThat(session.withoutAttachedFile("a.md").attachedFileNames()).containsExactly("photo.png");
    }
}
