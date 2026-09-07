package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.agent.port.out.PartContentReader;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.MessageRole;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a user message with media reads to the model: as content blocks when
 * the target reads that kind and the message is in the current turn, as a
 * placeholder line otherwise. Text-only messages are untouched.
 */
class MessageToLlmMessageMapperTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();

    private final Map<String, PartContentReader.Content> files = new HashMap<>();
    private final PartContentReader reader = id -> Optional.ofNullable(files.get(id));
    private final MessageToLlmMessageMapper mapper = new MessageToLlmMessageMapper(reader);

    private final AgentDefinition def = new AgentDefinition(AGENT, new Namespace("test"), "a", "d",
            null, null, "prompt", null, "cfg", 5, null, AgentDefinitionStatus.ACTIVE,
            List.of(), List.of(), null, null, null, null);
    private final AgentSession session = new AgentSession(UUID.randomUUID(), CONVERSATION, Namespace.DEFAULT,
            "user", AGENT, "t", SessionStatus.ACTIVE, Instant.now(), null, null, null, null,
            List.of(), List.of());
    private final ContextTokenBudget budget = new ContextTokenBudget(text -> text.length() / 4, 8192, 8192, 8192);

    private static final ContentPart.Image PHOTO = new ContentPart.Image("f-1", "photo.png", "image/png", 3);
    private static final ContentPart.File SPEC = new ContentPart.File("f-2", "spec.pdf", "application/pdf", 3);

    private static LlmConfig target(LlmCapability... capabilities) {
        return LlmConfig.claude("cfg", "claude-sonnet-4-6", "k").withCapabilities(Set.of(capabilities));
    }

    private static Message user(int seq, UUID turnId, List<ContentPart> parts) {
        return Message.of(CONVERSATION, UUID.randomUUID(), ParticipantType.USER, MessageType.CHAT, parts, seq)
                .withTurnId(turnId);
    }

    private static Message agent(int seq, UUID turnId, String text) {
        return Message.of(CONVERSATION, AGENT, ParticipantType.AGENT, MessageType.CHAT, text, seq)
                .withTurnId(turnId);
    }

    private void stored(String id, String bytes, String mediaType) {
        files.put(id, new PartContentReader.Content(bytes.getBytes(StandardCharsets.UTF_8), mediaType));
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void textOnlyMessagesAreOneTextBlockEachWhateverTheTarget() {
        UUID turn = UUID.randomUUID();
        List<Message> history = List.of(
                user(1, turn, ContentPart.text("hi")),
                agent(2, turn, "hello"));

        List<LlmMessage> out = mapper.toMessages(history, def, session, budget, null);

        assertThat(out).extracting(LlmMessage::role).containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(out.get(0).parts()).containsExactly(new LlmContent.Text("hi"));
        assertThat(out.get(1).content()).isEqualTo("hello");
    }

    @Test
    void anImageInTheCurrentTurnGoesInlineToAVisionModel() {
        stored("f-1", "PNG", "image/png");
        UUID turn = UUID.randomUUID();
        Message m = user(1, turn, List.of(new ContentPart.Text("what is this?"), PHOTO));

        List<LlmMessage> out = mapper.toMessages(List.of(m), def, session, budget, target(LlmCapability.VISION));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).parts()).containsExactly(
                new LlmContent.Text("what is this?"),
                new LlmContent.Image(base64("PNG"), "image/png"));
        assertThat(out.get(0).hasMedia()).isTrue();
    }

    @Test
    void aDocumentGoesInlineOnlyToADocumentReadingModel() {
        stored("f-2", "PDF", "application/pdf");
        Message m = user(1, UUID.randomUUID(), List.of(new ContentPart.Text("summarise"), SPEC));

        List<LlmMessage> docs = mapper.toMessages(List.of(m), def, session, budget, target(LlmCapability.DOCUMENTS));
        List<LlmMessage> vision = mapper.toMessages(List.of(m), def, session, budget, target(LlmCapability.VISION));

        assertThat(docs.get(0).parts()).containsExactly(
                new LlmContent.Text("summarise"),
                new LlmContent.Document(base64("PDF"), "application/pdf", "spec.pdf"));
        assertThat(vision.get(0).hasMedia()).isFalse();
        assertThat(vision.get(0).content())
                .startsWith("summarise\n[System note — document attached: spec.pdf (application/pdf, 3 B) — ")
                .contains("vector_search");
    }

    @Test
    void aModelThatDoesNotReadImagesGetsThePlaceholder() {
        stored("f-1", "PNG", "image/png");
        Message m = user(1, UUID.randomUUID(), List.of(new ContentPart.Text("look"), PHOTO));

        List<LlmMessage> noCaps = mapper.toMessages(List.of(m), def, session, budget, target());
        List<LlmMessage> noTarget = mapper.toMessages(List.of(m), def, session, budget, null);

        for (List<LlmMessage> out : List.of(noCaps, noTarget)) {
            assertThat(out.get(0).hasMedia()).isFalse();
            assertThat(out.get(0).content()).isEqualTo(
                    "look\n[System note — image attached: photo.png (image/png, 3 B) — "
                            + "this model does not read images, so it is not included.]");
        }
    }

    @Test
    void anImageFromAnEarlierTurnIsNotResent() {
        stored("f-1", "PNG", "image/png");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<Message> history = List.of(
                user(1, first, List.of(new ContentPart.Text("what is this?"), PHOTO)),
                agent(2, first, "a cat"),
                user(3, second, ContentPart.text("and its colour?")));

        List<LlmMessage> out = mapper.toMessages(history, def, session, budget, target(LlmCapability.VISION));

        assertThat(out.get(0).hasMedia()).isFalse();
        assertThat(out.get(0).content()).contains("sent in an earlier turn, not resent");
        assertThat(out.get(2).content()).isEqualTo("and its colour?");
    }

    @Test
    void thePlaceholderNamesTheViewerToolWhenTheSessionHasIt() {
        stored("f-1", "PNG", "image/png");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<Message> history = List.of(
                user(1, first, List.of(new ContentPart.Text("what is this?"), PHOTO)),
                agent(2, first, "a cat"),
                user(3, second, ContentPart.text("and its colour?")));
        AgentSession withViewer = session.withActivatedTools(List.of("view_attachment"));

        List<LlmMessage> out = mapper.toMessages(history, def, withViewer, budget, target(LlmCapability.VISION));

        assertThat(out.get(0).content()).contains("call view_attachment(\"photo.png\") to see it again");
    }

    @Test
    void aMessageInsertedWithinTheCurrentTurnStillGoesInline() {
        stored("f-1", "PNG", "image/png");
        UUID turn = UUID.randomUUID();
        // The runtime appended the image on the user's behalf, in the same
        // turn as the user's question: it is current, it goes inline.
        List<Message> history = List.of(
                user(1, turn, ContentPart.text("show me the photo again")),
                user(2, turn, List.of(new ContentPart.Text("(photo.png)"), PHOTO)));

        List<LlmMessage> out = mapper.toMessages(history, def, session, budget, target(LlmCapability.VISION));

        assertThat(out.get(1).hasMedia()).isTrue();
    }

    @Test
    void aFileTheStoreNoLongerHasBecomesAPlaceholderNotAnError() {
        Message m = user(1, UUID.randomUUID(), List.of(new ContentPart.Text("look"), PHOTO));

        List<LlmMessage> out = mapper.toMessages(List.of(m), def, session, budget, target(LlmCapability.VISION));

        assertThat(out.get(0).hasMedia()).isFalse();
        assertThat(out.get(0).content()).contains("no longer available");
    }

    @Test
    void aFileAboveTheInlineLimitBecomesAPlaceholder() {
        stored("f-1", "PNG", "image/png");
        ContentPart.Image huge = new ContentPart.Image("f-1", "huge.png", "image/png",
                MessageToLlmMessageMapper.MAX_INLINE_BYTES + 1);
        Message m = user(1, UUID.randomUUID(), List.of(new ContentPart.Text("look"), huge));

        List<LlmMessage> out = mapper.toMessages(List.of(m), def, session, budget, target(LlmCapability.VISION));

        assertThat(out.get(0).hasMedia()).isFalse();
        assertThat(out.get(0).content()).contains("too large to send inline (limit 20.0 MB)");
    }

    @Test
    void modelTextNamesTheMediaOnOneLineEach() {
        Message m = user(1, UUID.randomUUID(), List.of(new ContentPart.Text("look"), PHOTO, SPEC));

        assertThat(mapper.modelText(m, def, session)).isEqualTo(
                "look\n[image attached: photo.png (image/png, 3 B)]\n[document attached: spec.pdf (application/pdf, 3 B)]");
        assertThat(mapper.modelText(user(2, null, ContentPart.text("plain")), def, session)).isEqualTo("plain");
    }

    @Test
    void theStoredTextIsWhatTheUserTypedAndTheNoticeStillLeadsIt() {
        Message m = user(1, UUID.randomUUID(), List.of(new ContentPart.Text("what does it say?"), PHOTO))
                .withMetadata(Map.of(ai.mindconnect.agent.service.prompt.AttachmentNotice.ATTACHMENTS,
                        List.of("notes.md")));
        AgentSession withNotes = new AgentSession(session.id(), CONVERSATION, Namespace.DEFAULT,
                "user", AGENT, "t", SessionStatus.ACTIVE, Instant.now(), null, null, null, null,
                List.of(), List.of(ai.mindconnect.agent.domain.AttachedFile.named("notes.md")));

        List<LlmMessage> out = mapper.toMessages(List.of(m), def, withNotes, budget, target());

        assertThat(m.content()).isEqualTo("what does it say?");
        assertThat(out.get(0).content())
                .startsWith("[System note — attached to this chat: notes.md (Markdown)")
                .contains("what does it say?")
                .contains("image attached: photo.png");
    }
}
