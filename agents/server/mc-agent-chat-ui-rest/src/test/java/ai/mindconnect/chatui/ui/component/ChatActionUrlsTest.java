package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat composer and the per-message actions, pinned to the routes their
 * URL strings produced.
 *
 * <p>Two things here that the admin pages did not have. Send and Regenerate
 * are SSE triggers, so they go through {@code UiActions.streaming} and must
 * keep the STREAM behaviour — applying such a response in one go would break
 * live token rendering. And Delete-from-here carries two query parameters that
 * used to be concatenated by hand.
 */
class ChatActionUrlsTest {

    private static final UUID SESSION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID AGENT   = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void theComposerKeepsItsRoutes() throws Exception {
        String out = json(new ChatFormComponent(SESSION, AGENT).render());

        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION + "/attach-dialog\"");
        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION + "/settings\"");
        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION + "/chat/stream\"");
    }

    /** Send must stay a stream, not become an ordinary request. */
    @Test
    void sendKeepsTheStreamingBehaviour() throws Exception {
        String out = json(new ChatFormComponent(SESSION, AGENT).render());

        assertThat(out).contains("\"behavior\":\"STREAM\"");
    }

    /** While a turn runs the composer shows Stop, which cancels the session's stream. */
    @Test
    void stopCancelsTheSessionsStream() throws Exception {
        String out = json(new ChatFormComponent(SESSION, AGENT, true).render());

        assertThat(out).contains("\"url\":\"/chat/api/streams/msg-list-" + SESSION + "\"");
        assertThat(out).contains("\"method\":\"DELETE\"");
    }

    @Test
    void aUserMessageCanBeRegeneratedAndDeletedFromHere() throws Exception {
        var message = Message.of(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.USER,
                MessageType.CHAT, "hi", 7);
        var item = new MessageComponent(SESSION, null, message, true,
                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())).item();

        String out = json(item);

        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION + "/messages/7/regenerate\"");
        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION
                + "/messages?fromSeq=7&toSeq=" + Integer.MAX_VALUE + "\"");
    }
}
