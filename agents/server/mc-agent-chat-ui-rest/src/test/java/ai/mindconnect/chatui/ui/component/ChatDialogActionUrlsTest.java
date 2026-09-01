package ai.mindconnect.chatui.ui.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat page's dialogs and shell: approval cards, the settings dialog, the
 * sidebar's New chat, and removing an attachment.
 *
 * <p>The approval card is the one worth watching. Its three buttons differed
 * only in two query values glued onto a shared base string; they are now
 * arguments, so the builder writes the query and encodes callId.
 */
class ChatDialogActionUrlsTest {

    private static final UUID SESSION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void theThreeApprovalButtonsDifferOnlyInTheirAnswer() throws Exception {
        String out = json(ApprovalCardComponent.approvalCard(
                SESSION, "call-1", "bash", "{}", "12:00"));

        String base = "/chat/api/sessions/" + SESSION + "/approval?callId=call-1";
        assertThat(out).contains("\"url\":\"" + base + "&approved=false&scope=once\"");
        assertThat(out).contains("\"url\":\"" + base + "&approved=true&scope=once\"");
        assertThat(out).contains("\"url\":\"" + base + "&approved=true&scope=session\"");
    }

    /** A callId with URL-significant characters must survive as one value. */
    @Test
    void theCallIdIsEncodedByTheBuilder() throws Exception {
        String out = json(ApprovalCardComponent.approvalCard(
                SESSION, "call/with space", "bash", "{}", "12:00"));

        assertThat(out).doesNotContain("callId=call/with space");
        assertThat(out).contains("callId=call/with%20space&approved=");
    }

    @Test
    void theSettingsDialogAppliesAndCloses() throws Exception {
        var settings = new ChatSettingsComponent(SESSION, List.of(), List.of(), List.of(),
                null, List.of(), false, null);

        String out = json(settings.render());

        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION + "/settings\"");
        assertThat(out).contains("\"url\":\"/chat/api/close-dialog\"");
    }

    @Test
    void removingAnAttachmentKeepsTheRowPlaceholder() throws Exception {
        String out = json(ChatAttachmentsComponent.node(SESSION, Map.of("a.pdf", 3L)));

        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION + "/chat-files?file={id}\"");
        assertThat(out).doesNotContain("%7Bid%7D");
    }
}
