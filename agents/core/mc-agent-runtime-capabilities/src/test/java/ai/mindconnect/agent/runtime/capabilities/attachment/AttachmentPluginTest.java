package ai.mindconnect.agent.runtime.capabilities.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.TurnAdmission;
import ai.mindconnect.agent.runtime.api.hook.TurnInput;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

/** Plays the engine's part by hand: what a session's attachments do to a turn's input, instructions and model call. */
class AttachmentPluginTest {

    private static final AgentDefinition HELPER = new AgentDefinition("helper", "Helps.", "You help.",
            ModelSettings.NONE, List.of(), 5, Map.of());
    private static final Map<String, Object> OWL = Map.of("id", "f-1", "name", "owl.png", "mediaType", "image/png",
            "sizeBytes", 3);
    private static final Map<String, Object> NOTES = Map.of("id", "f-2", "name", "notes.docx", "sizeBytes", 9);
    private static final ContentPart.Image OWL_PART = new ContentPart.Image(new MediaSource.FileId("f-1"),
            ContentPart.Image.Detail.AUTO, "owl.png");

    private final List<TurnMessage> history = new ArrayList<>();
    private PluginRegistry registry;

    @BeforeEach
    void install_the_plugin() {
        registry = new DefaultPluginRegistry();
        registry.provide(SessionStore.class, () -> new SessionStore() {
            @Override
            public void create(Session session) {
            }

            @Override
            public Optional<Session> find(SessionId sessionId) {
                return Optional.empty();
            }

            @Override
            public Session update(SessionId sessionId, UnaryOperator<Session> change) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<TurnMessage> messages(SessionId sessionId) {
                return history;
            }

            @Override
            public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> messages) {
                return messages;
            }

            @Override
            public void updateMessageMetadata(SessionId sessionId, String messageId,
                                              UnaryOperator<Map<String, Object>> change) {
            }
        });
        registry.install(new AttachmentPlugin());
        registry.start();
    }

    private static Session session(Object... attached) {
        return new Session(SessionId.of("s1"), UserId.of("ada"), null, HELPER,
                Map.of(AttachmentPlugin.ATTACHMENTS, List.of(attached)), Instant.EPOCH);
    }

    private TurnRequest admitted(Session session, String text) {
        TurnAdmission admission = registry.run(RuntimeHook.TURN_INPUT,
                new TurnInput(session, TurnRequest.text(session.id(), text)),
                input -> new TurnAdmission.Admitted(input.request()));
        return ((TurnAdmission.Admitted) admission).request();
    }

    private List<TurnMessage> asked(Session session, String turnId, List<ToolDefinition> tools) {
        ModelCall call = new ModelCall(new AgentContext(session, HELPER, TurnId.of(turnId)),
                new ModelRequest(TurnId.of(turnId), ModelSettings.NONE, "You help.", history, tools));
        List<TurnMessage> sent = new ArrayList<>();
        registry.run(RuntimeHook.MODEL_CALL, call, reached -> {
            sent.addAll(reached.request().messages());
            return null;
        });
        return sent;
    }

    @Test
    void the_first_message_after_a_change_announces_it_and_carries_the_new_image() {
        TurnRequest first = admitted(session(OWL, NOTES), "What is this?");

        assertThat(first.input()).containsExactly(new ContentPart.Text("What is this?"), OWL_PART);
        assertThat(first.metadata()).containsEntry(AttachmentPlugin.ANNOUNCED, List.of("owl.png", "notes.docx"));

        // What was announced is in the history; the next turn says nothing again, a removal is said once.
        history.add(new TurnMessage("m1", "t1", TurnMessage.Type.USER, first.input(), List.of(), first.metadata()));
        assertThat(admitted(session(OWL, NOTES), "And now?").metadata()).isEmpty();
        TurnRequest afterRemoval = admitted(session(OWL), "Still there?");
        assertThat(afterRemoval.input()).hasSize(1);
        assertThat(afterRemoval.metadata()).isEqualTo(Map.of(AttachmentPlugin.DETACHED, List.of("notes.docx")));
    }

    @Test
    void whoever_inspects_the_session_reads_the_same_notice_without_a_model_call() {
        TurnRequest first = admitted(session(OWL, NOTES), "What is this?");
        TurnMessage stored = new TurnMessage("m1", "t1", TurnMessage.Type.USER, first.input(), List.of(),
                first.metadata());

        TurnMessage read = registry.all(ai.mindconnect.agent.runtime.capabilities.memory.MessageAsSent.class)
                .get(0).of(stored, session(OWL, NOTES));

        assertThat(((ContentPart.Text) read.content().get(0)).text())
                .startsWith("[System note — attached to this chat: notes.docx (Word document)");
        assertThat(read.id()).isEqualTo("m1");
    }

    @Test
    void the_model_reads_a_notice_ahead_of_the_users_text_for_what_still_holds() {
        TurnRequest first = admitted(session(OWL, NOTES), "What is this?");
        history.add(new TurnMessage("m1", "t1", TurnMessage.Type.USER, first.input(), List.of(), first.metadata()));

        TurnMessage read = asked(session(OWL, NOTES), "t1", List.of()).get(0);
        assertThat(((ContentPart.Text) read.content().get(0)).text())
                .startsWith("[System note — attached to this chat: notes.docx (Word document)")
                .doesNotContain("owl.png");
        assertThat(read.content()).contains(new ContentPart.Text("What is this?"), OWL_PART);
        // The session keeps what the user typed, and a file removed since is no longer announced.
        assertThat(history.get(0).content()).hasSize(2);
        assertThat(asked(session(OWL), "t1", List.of()).get(0).content()).hasSize(2);
    }

    @Test
    void the_instructions_list_what_is_searched_and_the_viewer_is_offered_for_what_can_be_shown() {
        AgentContext withBoth = new AgentContext(session(OWL, NOTES), HELPER, TurnId.of("t1"));
        AgentContext withNone = new AgentContext(session(), HELPER, TurnId.of("t1"));

        assertThat(registry.run(RuntimeHook.INSTRUCTIONS, withBoth, context -> "You help."))
                .contains("## Attached files").contains("- notes.docx (Word document)").doesNotContain("owl.png");
        assertThat(registry.run(RuntimeHook.INSTRUCTIONS, withNone, context -> "You help.")).isEqualTo("You help.");
        assertThat(registry.run(RuntimeHook.TOOL_OFFER, withBoth, context -> List.<ToolDefinition>of()))
                .extracting(ToolDefinition::name).containsExactly("view_attachment");
        assertThat(registry.run(RuntimeHook.TOOL_OFFER, withNone, context -> List.<ToolDefinition>of())).isEmpty();
    }

    @Test
    void a_file_asked_for_again_follows_the_results_of_the_round_in_the_turn_underway() {
        history.add(TurnMessage.toolCalls(List.of(
                new TurnMessage.ToolCall("c1", "view_attachment", Map.of("name", "owl.png")),
                new TurnMessage.ToolCall("c2", "bash", Map.of()))).inTurn("t2"));
        history.add(TurnMessage.toolResult("c1", "view_attachment", "Showing 'owl.png'", false).inTurn("t2"));
        history.add(TurnMessage.toolResult("c2", "bash", "done", false).inTurn("t2"));

        List<TurnMessage> sent = asked(session(OWL), "t2", List.of());

        assertThat(sent).hasSize(4);
        assertThat(sent.get(3).type()).isEqualTo(TurnMessage.Type.USER);
        assertThat(sent.get(3).turnId()).isEqualTo("t2");
        assertThat(sent.get(3).content()).contains(OWL_PART);
        assertThat(asked(session(OWL), "t3", List.of())).as("a later turn").hasSize(3);
    }
}
