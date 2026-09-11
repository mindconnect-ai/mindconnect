package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.AttachedFile;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.message.domain.ContentPart;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentPartsTest {

    private static AgentSession session(AttachedFile... files) {
        return new AgentSession(UUID.randomUUID(), UUID.randomUUID(), Namespace.DEFAULT, "u",
                UUID.randomUUID(), "t", SessionStatus.ACTIVE, Instant.now(), null, null, null, null,
                List.of(), List.of(files));
    }

    private static final AttachedFile PHOTO = new AttachedFile("f-1", "photo.png", "image/png", 240);
    private static final AttachedFile SPEC = new AttachedFile("f-2", "spec.pdf", "application/pdf", 1024);
    private static final AttachedFile NOTES = new AttachedFile("f-3", "notes.md", "text/markdown", 12);

    @Test
    void freshImagesAndPdfsRideAlongAsParts_otherFilesDoNot() {
        List<ContentPart> parts = AttachmentParts.withAttachments(ContentPart.text("look"),
                session(PHOTO, SPEC, NOTES), List.of("photo.png", "spec.pdf", "notes.md"));

        assertThat(parts).containsExactly(
                new ContentPart.Text("look"),
                new ContentPart.Image("f-1", "photo.png", "image/png", 240),
                new ContentPart.File("f-2", "spec.pdf", "application/pdf", 1024));
    }

    @Test
    void onlyTheFilesAnnouncedByThisMessageAreSent() {
        List<ContentPart> parts = AttachmentParts.withAttachments(ContentPart.text("look"),
                session(PHOTO, SPEC), List.of("spec.pdf"));

        assertThat(parts).containsExactly(
                new ContentPart.Text("look"),
                new ContentPart.File("f-2", "spec.pdf", "application/pdf", 1024));
    }

    @Test
    void aFileTheCallerAlreadySendsIsNotAddedAgain() {
        // The REST body and the protocol bridge reference the upload by id.
        List<ContentPart> parts = AttachmentParts.withAttachments(
                List.of(new ContentPart.Text("look"),
                        new ContentPart.Image("f-1", "photo.png", "image/png", 240)),
                session(PHOTO, SPEC), List.of("photo.png", "spec.pdf"));

        assertThat(parts).containsExactly(
                new ContentPart.Text("look"),
                new ContentPart.Image("f-1", "photo.png", "image/png", 240),
                new ContentPart.File("f-2", "spec.pdf", "application/pdf", 1024));
    }

    @Test
    void nothingFreshOrNoSessionLeavesThePartsAlone() {
        List<ContentPart> text = ContentPart.text("look");
        assertThat(AttachmentParts.withAttachments(text, session(PHOTO), List.of())).isSameAs(text);
        assertThat(AttachmentParts.withAttachments(text, null, List.of("photo.png"))).isSameAs(text);
    }

    @Test
    void aLegacyEntryWithoutAnIdAndAnUnknownNameAddNothing() {
        List<ContentPart> parts = AttachmentParts.withAttachments(ContentPart.text("look"),
                session(AttachedFile.named("old.png")), List.of("old.png", "gone.png"));

        assertThat(parts).containsExactly(new ContentPart.Text("look"));
    }
}
