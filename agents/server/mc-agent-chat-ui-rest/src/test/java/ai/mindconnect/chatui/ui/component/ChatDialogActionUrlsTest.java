package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat page's dialogs and shell: approval cards, the settings dialog, the
 * sidebar's New chat, and removing an attachment.
 *
 * <p>The approval card is the one worth watching. Its three buttons differed
 * only in two query values glued onto a shared base string; they are now
 * arguments, so the builder writes the query and encodes callId.
 *
 * <p>URLs carry the bare session id value, never {@code namespace/value}.
 */
class ChatDialogActionUrlsTest {

    private static final String SESSION_VALUE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final SessionId SESSION = SessionId.of(SESSION_VALUE);

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void theThreeApprovalButtonsDifferOnlyInTheirAnswer() throws Exception {
        String out = json(ApprovalCardComponent.approvalCard(
                SESSION, "call-1", "bash", "{}", "12:00"));

        String base = "/chat/api/sessions/" + SESSION_VALUE + "/approval?callId=call-1";
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
        var settings = new ChatSettingsComponent(SESSION, List.of(), List.of(), null, null, null);

        String out = json(settings.render());

        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION_VALUE + "/settings\"");
        assertThat(out).contains("\"url\":\"/chat/api/close-dialog\"");
    }

    /**
     * Three fields, and the two that left. The tool multiselect and the
     * tool-search checkbox moved behind the composer's "+", where they are
     * switches rather than a form — and the dialog says so, because one that
     * silently loses half its contents teaches people the feature was removed.
     */
    @Test
    void theSettingsDialogIsTheAgentTheModelAndThePrompt() throws Exception {
        String out = json(new ChatSettingsComponent(SESSION, List.of(), List.of(),
                "agent-default", null, "You are helpful.").render());

        // A field's id IS its form-control name, so these are the three keys
        // applySettings reads out of the payload.
        assertThat(out).contains("\"id\":\"agentId\"")
                .contains("\"id\":\"llmConfigName\"")
                .contains("\"id\":\"systemPrompt\"");
        assertThat(out)
                .as("tools are switched in the \"+\" menu now, not applied from a form")
                .doesNotContain("\"id\":\"tools\"")
                .doesNotContain("\"id\":\"toolSearch\"")
                .doesNotContain("MULTISELECT");
        assertThat(out).contains("Tools and sub-agents are switched in the composer");
    }

    /** No tabs for three fields — the second tab existed to hold the tool list. */
    @Test
    void theSettingsDialogHasNoTabs() throws Exception {
        assertThat(json(new ChatSettingsComponent(SESSION, List.of(), List.of(), null, null, null)
                .render()))
                .doesNotContain("\"type\":\"section\"");
    }

    @Test
    void removingAnAttachmentKeepsTheRowPlaceholder() throws Exception {
        String out = json(ChatAttachmentsComponent.node(SESSION,
                List.of(new AttachedFile("f-1", "a.pdf", "application/pdf", 10),
                        new AttachedFile("f-2", "photo.png", "image/png", 20)),
                Map.of("a.pdf", 3L)));

        assertThat(out).contains("\"url\":\"/chat/api/sessions/" + SESSION_VALUE + "/chat-files?file={id}\"");
        assertThat(out).doesNotContain("%7Bid%7D");
        assertThat(out).contains("document with the next message · 3 searchable chunks");
        assertThat(out).contains("image with the next message");
    }
}
