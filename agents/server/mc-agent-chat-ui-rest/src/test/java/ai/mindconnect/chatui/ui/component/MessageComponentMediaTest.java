package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A user message's media parts in its bubble: the image itself, a document by name. */
class MessageComponentMediaTest {

    private static final UUID SESSION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static MessageComponent component(Message m) {
        return new MessageComponent(SESSION, null, m, true, DateTimeFormatter.ISO_INSTANT);
    }

    @Test
    void anImagePartRendersAsThePictureFromTheChatsFileEndpoint() {
        Message m = Message.of(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT,
                List.of(new ContentPart.Text("what is this?"),
                        new ContentPart.Image("f-1", "my [photo].png", "image/png", 3),
                        new ContentPart.File("f-2", "spec.pdf", "application/pdf", 3)), 1);

        String body = component(m).withAttachmentChip(m);

        assertThat(body).startsWith("what is this?");
        String url = "/chat/api/sessions/" + SESSION + "/chat-files/f-1/content";
        assertThat(body).contains("[![my photo.png](" + url + ")](" + url + ")");
        assertThat(body).contains("📄 *spec.pdf*");
    }

    @Test
    void aTextMessageIsItsTextAlone() {
        Message m = Message.of(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT,
                "hello", 1);

        assertThat(component(m).withAttachmentChip(m)).isEqualTo("hello");
    }
}
