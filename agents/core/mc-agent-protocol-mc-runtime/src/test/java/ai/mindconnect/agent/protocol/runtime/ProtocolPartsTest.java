package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.Role;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolPartsTest {

    @Test
    void aStoredImageBecomesAnImagePartReferencingItsId() {
        StoredFile stored = new StoredFile("f-1", "photo.png", "image/png", 240, Instant.now());

        assertThat(ProtocolParts.image(stored))
                .isEqualTo(new ai.mindconnect.message.domain.ContentPart.Image("f-1", "photo.png", "image/png", 240));
    }

    @Test
    void aStoredMessageReadsBackWithItsMediaAsFileIdSources() {
        Message m = Message.of(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT, List.of(
                new ai.mindconnect.message.domain.ContentPart.Text("what is this?"),
                new ai.mindconnect.message.domain.ContentPart.Image("f-1", "photo.png", "image/png", 240),
                new ai.mindconnect.message.domain.ContentPart.File("f-2", "spec.pdf", "application/pdf", 1024)), 1);

        ConversationItem.Message item = ProtocolParts.message(Role.USER, m);

        assertThat(item.role()).isEqualTo(Role.USER);
        assertThat(item.content()).containsExactly(
                new ContentPart.Text("what is this?"),
                ContentPart.Image.of(new ContentPart.MediaSource.FileId("f-1")),
                new ContentPart.Document(new ContentPart.MediaSource.FileId("f-2"), "spec.pdf"));
    }

    @Test
    void aTextMessageIsOneTextPart_theCompressedStubWhenThereIsOne() {
        Message m = Message.of(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.AGENT, MessageType.CHAT, "long", 1);

        assertThat(ProtocolParts.message(Role.ASSISTANT, m).content())
                .containsExactly(new ContentPart.Text("long"));
        assertThat(ProtocolParts.message(Role.ASSISTANT, m.withCompressed("[stub]", 1)).content())
                .containsExactly(new ContentPart.Text("[stub]"));
    }
}
