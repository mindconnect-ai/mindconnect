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

    @Test
    void everyAttachmentIsFreshUntilAUserMessageAnnouncedIt() {
        AgentSession s = session("notes.md", "report.pdf");
        assertThat(AttachmentNotice.unannounced(s, List.of())).containsExactly("notes.md", "report.pdf");

        List<Message> history = List.of(
                user("what is in notes?", Map.of(AttachmentNotice.ATTACHMENTS, List.of("notes.md"))),
                agent("…"));
        assertThat(AttachmentNotice.unannounced(s, history)).containsExactly("report.pdf");

        List<Message> both = List.of(
                user("q1", Map.of(AttachmentNotice.ATTACHMENTS, List.of("notes.md"))),
                user("q2", Map.of(AttachmentNotice.ATTACHMENTS, List.of("report.pdf"))));
        assertThat(AttachmentNotice.unannounced(s, both)).isEmpty();
    }

    @Test
    void anAgentMessageWithTheSameMetadataDoesNotCount() {
        AgentSession s = session("notes.md");
        Message agentWithMeta = agent("x").withMetadata(Map.of(AttachmentNotice.ATTACHMENTS, List.of("notes.md")));
        assertThat(AttachmentNotice.unannounced(s, List.of(agentWithMeta))).containsExactly("notes.md");
    }

    @Test
    void theNoticeNamesTheFilesTheirKindAndTheOneToolThatReachesThem() {
        String notice = AttachmentNotice.notice(List.of("notes.md", "Abrechnung.pdf", "data"));
        assertThat(notice).startsWith("[System note — attached to this chat: notes.md (Markdown), Abrechnung.pdf (PDF), data (file)")
                .contains("vector_search")
                .contains("not on the filesystem")
                .endsWith("]");
        assertThat(AttachmentNotice.notice(List.of())).isEmpty();
        assertThat(AttachmentNotice.metadata(List.of("a.pdf"))).containsEntry(AttachmentNotice.ATTACHMENTS, List.of("a.pdf"));
        assertThat(AttachmentNotice.metadata(List.of())).isEmpty();
    }

    @Test
    void theModelSeesTheNoticeAheadOfTheTextButTheStoredTextIsUntouched() {
        Message announcing = user("What does it say?", Map.of(AttachmentNotice.ATTACHMENTS, List.of("notes.md")));
        assertThat(announcing.content()).isEqualTo("What does it say?");
        assertThat(AttachmentNotice.announcedBy(announcing)).containsExactly("notes.md");
        assertThat(AttachmentNotice.forModel(announcing))
                .startsWith("[System note — attached to this chat: notes.md (Markdown)")
                .endsWith("]\n\nWhat does it say?");

        Message plain = user("plain", Map.of());
        assertThat(AttachmentNotice.announcedBy(plain)).isEmpty();
        assertThat(AttachmentNotice.forModel(plain)).isEqualTo("plain");
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
