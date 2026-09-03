package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentNoticeTest {

    private static AgentSession session(String... attached) {
        return new AgentSession(UUID.randomUUID(), UUID.randomUUID(), Namespace.DEFAULT, "david",
                UUID.randomUUID(), "t", SessionStatus.ACTIVE, Instant.now(), null, null, null, null,
                List.of(), List.of(attached));
    }

    private static Message user(String text, Map<String, Object> metadata) {
        return Message.of(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT, text, 1)
                .withMetadata(metadata);
    }

    private static Message agent(String text) {
        return Message.of(UUID.randomUUID(), UUID.randomUUID(), ParticipantType.AGENT, MessageType.CHAT, text, 2);
    }

    private static Map<String, Object> attached(String... names) {
        return Map.of(AttachmentNotice.ATTACHMENTS, List.of(names));
    }

    @Test
    void everyAttachmentIsFreshUntilAUserMessageAnnouncedIt() {
        AgentSession s = session("notes.md", "report.pdf");
        assertThat(AttachmentNotice.unannounced(s, List.of())).containsExactly("notes.md", "report.pdf");

        List<Message> history = List.of(user("what is in notes?", attached("notes.md")), agent("…"));
        assertThat(AttachmentNotice.unannounced(s, history)).containsExactly("report.pdf");

        List<Message> both = List.of(user("q1", attached("notes.md")), user("q2", attached("report.pdf")));
        assertThat(AttachmentNotice.unannounced(s, both)).isEmpty();
    }

    @Test
    void anAgentMessageWithTheSameMetadataDoesNotCount() {
        Message agentWithMeta = agent("x").withMetadata(attached("notes.md"));
        assertThat(AttachmentNotice.unannounced(session("notes.md"), List.of(agentWithMeta))).containsExactly("notes.md");
    }

    @Test
    void aRemovalIsAnnouncedOnceAndAReattachAfterItIsAnnouncedAgain() {
        List<Message> announced = List.of(user("q1", attached("a.pdf")));

        // removed since: the next turn announces the removal
        assertThat(AttachmentNotice.unannouncedRemovals(session(), announced)).containsExactly("a.pdf");
        assertThat(AttachmentNotice.unannounced(session(), announced)).isEmpty();
        Map<String, Object> meta = AttachmentNotice.metadata(List.of(), List.of("a.pdf"));
        assertThat(meta).containsEntry(AttachmentNotice.DETACHED, List.of("a.pdf")).doesNotContainKey(AttachmentNotice.ATTACHMENTS);

        // once announced, the removal is not repeated
        List<Message> withRemoval = List.of(announced.get(0), user("q2", meta));
        assertThat(AttachmentNotice.unannouncedRemovals(session(), withRemoval)).isEmpty();

        // attached again after the removal: fresh, because the record is read in order
        assertThat(AttachmentNotice.unannounced(session("a.pdf"), withRemoval)).containsExactly("a.pdf");
        assertThat(AttachmentNotice.unannouncedRemovals(session("a.pdf"), withRemoval)).isEmpty();
    }

    @Test
    void theNoticesNameWhatStillHoldsAndTheStoredTextIsUntouched() {
        Message announcing = user("What does it say?", attached("notes.md", "b.pdf"));
        assertThat(announcing.content()).isEqualTo("What does it say?");
        assertThat(AttachmentNotice.forModel(announcing, session("notes.md", "b.pdf")))
                .startsWith("[System note — attached to this chat: notes.md (Markdown), b.pdf (PDF)")
                .contains("vector_search").contains("not on the filesystem")
                .endsWith("]\n\nWhat does it say?");
        assertThat(AttachmentNotice.forModel(announcing, session("b.pdf"))).as("notes.md removed since")
                .contains("attached to this chat: b.pdf (PDF)").doesNotContain("notes.md");
        assertThat(AttachmentNotice.forModel(announcing, session())).isEqualTo("What does it say?");

        Message removing = user("who wrote it?", AttachmentNotice.metadata(List.of(), List.of("notes.md")));
        assertThat(AttachmentNotice.forModel(removing, session()))
                .startsWith("[System note — removed from this chat: notes.md — the content is no longer available")
                .contains("do not search for it")
                .endsWith("]\n\nwho wrote it?");
        assertThat(AttachmentNotice.forModel(removing, session("notes.md"))).as("attached again since: the removal no longer holds")
                .isEqualTo("who wrote it?");

        Message plain = user("plain", Map.of());
        assertThat(AttachmentNotice.forModel(plain, session("notes.md"))).isEqualTo("plain");
        assertThat(AttachmentNotice.metadata(List.of(), List.of())).isEmpty();
    }

    @Test
    void theSystemPromptSectionListsKindsAndForbidsThePathTools() {
        String section = SystemPromptRenderer.attachedFilesSection(session("notes.md", "deck.pptx"));
        assertThat(section).contains("- notes.md (Markdown)")
                .contains("- deck.pptx (presentation)")
                .contains("`vector_search`")
                .contains("NOT files on the filesystem")
                .contains("`document_outline`");
        assertThat(SystemPromptRenderer.attachedFilesSection(session())).isEmpty();
    }
}
