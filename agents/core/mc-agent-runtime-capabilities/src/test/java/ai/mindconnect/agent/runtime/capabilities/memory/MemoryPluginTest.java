package ai.mindconnect.agent.runtime.capabilities.memory;

import static org.assertj.core.api.Assertions.tuple;
import ai.mindconnect.agent.runtime.api.port.out.PromptRenderer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.BackgroundModel;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;

/** A model that reads 100 tokens, a token per character, and a history of three turns of 40 tokens each. */
class MemoryPluginTest {

    private static final Session SESSION =
            new Session(SessionId.of("s1"), UserId.of("ada"), null, null, Map.of(), Instant.EPOCH);
    private static final List<TurnMessage> HISTORY = List.of(
            said("m1", TurnMessage.Type.USER, "first question ...."),
            said("m2", TurnMessage.Type.ASSISTANT, "first answer ......."),
            said("m3", TurnMessage.Type.USER, "second question ..."),
            said("m4", TurnMessage.Type.ASSISTANT, "second answer ......"),
            said("m5", TurnMessage.Type.USER, "third question ....."));

    private final List<ModelRequest> summaryRequests = new ArrayList<>();
    /** The notes the plugin asked the store to keep, by message id. */
    private final Map<String, String> remembered = new HashMap<>();
    /** The session the store finds; none until a test puts one there. */
    private Session stored;
    private PluginRegistry registry;

    @BeforeEach
    void install_memory() {
        registry = new DefaultPluginRegistry();
        registry.provide(TokenCounter.class, () -> String::length);
        registry.provide(PromptRenderer.class, () -> (template, variables) -> template);
        registry.provide(ModelGateway.class, () -> new ModelGateway() {
            @Override
            public LlmAnswer ask(ModelRequest request, TurnEventListener events) {
                summaryRequests.add(request);
                return LlmAnswer.of(List.of(TurnMessage.assistant("SUMMARY " + summaryRequests.size())), new Usage(1, 1));
            }

            @Override
            public OptionalInt contextWindowTokens(ModelSettings settings) {
                return OptionalInt.of(100);
            }

            @Override
            public void cancel(TurnId turnId) {
            }
        });
        registry.provide(SessionStore.class, () -> new SessionStore() {
            @Override
            public void updateMessageMetadata(SessionId sessionId, String messageId,
                                              UnaryOperator<Map<String, Object>> change) {
                remembered.put(messageId, (String) change.apply(Map.of()).get(SessionStore.COMPRESSED));
            }

            @Override
            public void create(Session session) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Session> find(SessionId sessionId) {
                return Optional.ofNullable(stored);
            }

            @Override
            public Session update(SessionId sessionId, UnaryOperator<Session> change) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<TurnMessage> messages(SessionId sessionId) {
                return HISTORY;
            }

            @Override
            public List<TurnMessage> append(SessionId sessionId, List<TurnMessage> messages) {
                throw new UnsupportedOperationException();
            }
        });
        registry.install(new MemoryPlugin(new InMemorySummaryStore(ScopeSupplier.fixed(new Namespace("test")))));
        registry.start();
    }

    @Test
    void looking_at_the_working_memory_shows_what_would_be_sent_and_changes_nothing() {
        stored = new Session(SessionId.of("s1"), UserId.of("ada"), null,
                new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.NONE, List.of(), 5,
                        Map.of(MemoryPlugin.MEMORY, Map.of("strategy", "compact", "maxConversationRatio", 0.9))),
                Map.of(), Instant.EPOCH);
        registry.after(RuntimeHook.INSTRUCTIONS, (context, instructions) -> instructions + " Be brief.");

        WorkingMemory memory = registry.get(MemoryInspection.class).inspect(SessionId.of("s1"));

        assertThat(memory.instructions()).isEqualTo("You help. Be brief.");
        assertThat(memory.contextWindowTokens()).hasValue(100);
        // The two turns before the one underway would be summarised first: they are not sent as said.
        assertThat(memory.messages()).extracting(entry -> entry.message().id(), WorkingMemory.Entry::sent)
                .containsExactly(tuple("m1", false), tuple("m2", false), tuple("m3", false), tuple("m4", false),
                        tuple("m5", true));
        assertThat(memory.totalTokens()).isEqualTo("You help. Be brief.".length() + "third question .....".length());
        // Nothing was summarised and nothing kept: looking again finds the same.
        assertThat(summaryRequests).isEmpty();
        assertThat(remembered).isEmpty();
        assertThat(memory.summary()).isEmpty();
    }

    @Test
    void the_working_memory_shows_a_message_as_the_other_capabilities_hand_it_to_the_model() {
        stored = new Session(SessionId.of("s1"), UserId.of("ada"), null,
                new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.NONE, List.of(), 5,
                        Map.of(MemoryPlugin.MEMORY, Map.of("strategy", "window", "maxConversationRatio", 10.0))),
                Map.of(), Instant.EPOCH);
        registry.contribute(MessageAsSent.class, (message, session) -> message.type() == TurnMessage.Type.USER
                ? said(message.id(), message.type(), "[note] " + message.text()) : message);

        WorkingMemory memory = registry.get(MemoryInspection.class).inspect(SessionId.of("s1"));

        assertThat(memory.messages().get(0).message().text()).isEqualTo("[note] first question ....");
        assertThat(memory.messages().get(0).sentAs()).as("added to, not shortened").isNull();
        assertThat(memory.messages().get(1).message().text()).isEqualTo("first answer .......");
    }

    @Test
    void an_agent_that_asks_for_the_full_history_gets_it() {
        assertThat(texts(sent(Map.of("strategy", "full"), 0.5))).hasSize(5);
        assertThat(summaryRequests).isEmpty();
    }

    @Test
    void an_agent_without_memory_is_sent_only_the_turn_underway() {
        assertThat(texts(sent(Map.of("strategy", "none"), 1.0))).containsExactly("third question .....");
        assertThat(summaryRequests).isEmpty();
    }

    @Test
    void a_window_can_be_bounded_by_messages_counted_in_whole_turns() {
        // Three messages would cut the second turn in two; the window takes the turns that fit whole.
        assertThat(texts(sent(Map.of("strategy", "window", "maxMessages", 3), 1.0)))
                .containsExactly("second question ...", "second answer ......", "third question .....");
        assertThat(texts(sent(Map.of("strategy", "window", "maxMessages", 2), 1.0)))
                .containsExactly("third question .....");
    }

    @Test
    void a_window_leaves_out_the_oldest_turns_whole() {
        ModelRequest sent = sent(Map.of("strategy", "window"), 0.7);

        assertThat(texts(sent)).containsExactly("second question ...", "second answer ......", "third question .....");
        assertThat(summaryRequests).isEmpty();
    }

    @Test
    void a_summary_is_made_by_the_model_the_assembly_names_for_such_tasks() {
        sent(Map.of("strategy", "compact"), 0.9);
        assertThat(summaryRequests.get(0).settings()).as("without one: the agent's own").isEqualTo(ModelSettings.NONE);

        install_memory();
        registry.provide(BackgroundModel.class, () -> BackgroundModel.named("small"));
        summaryRequests.clear();
        sent(Map.of("strategy", "compact"), 0.9);
        assertThat(summaryRequests.get(0).settings().model()).isEqualTo("small");
    }

    @Test
    void compact_summarises_what_it_leaves_out_and_does_so_once() {
        ModelRequest first = sent(Map.of("strategy", "compact"), 0.9);
        ModelRequest second = sent(Map.of("strategy", "compact"), 0.9);

        assertThat(summaryRequests).hasSize(1);
        // Half the budget stays as said, and that is the turn underway; the two before it are summarised.
        assertThat(summaryRequests.get(0).messages().get(0).text()).contains("first question", "second answer")
                .doesNotContain("third question");
        for (ModelRequest sent : List.of(first, second)) {
            assertThat(sent.instructions()).startsWith("You help.").endsWith("SUMMARY 1");
            assertThat(texts(sent)).containsExactly("third question .....");
        }
    }

    @Test
    void asked_to_a_session_is_summarised_up_to_its_last_turn_and_only_what_no_summary_covers() {
        MemoryCompaction compaction = registry.get(MemoryCompaction.class);
        stored = new Session(SESSION.id(), SESSION.userId(), null, new AgentDefinition("helper", "Helps.",
                "You help.", ModelSettings.NONE, List.of(), 5,
                Map.of(MemoryPlugin.MEMORY, Map.of("strategy", "compact"))), Map.of(), Instant.EPOCH);

        assertThat(compaction.compact(SESSION.id())).isEqualTo(4);
        assertThat(summaryRequests.get(0).messages().get(0).text()).contains("first question", "second answer")
                .doesNotContain("third question");
        assertThat(compaction.compact(SESSION.id())).as("nothing new to cover").isZero();
        // The next model call starts from that summary and does not make another.
        assertThat(sent(Map.of("strategy", "compact"), 0.9).instructions()).endsWith("SUMMARY 1");
        assertThat(summaryRequests).hasSize(1);

        stored = new Session(SESSION.id(), SESSION.userId(), null, new AgentDefinition("helper", "Helps.",
                "You help.", ModelSettings.NONE, List.of(), 5, Map.of()), Map.of(), Instant.EPOCH);
        assertThat(compaction.compact(SESSION.id())).as("an agent that keeps no summary").isZero();
    }

    @Test
    void the_summary_can_be_placed_as_a_message_of_the_user() {
        ModelRequest sent = sent(Map.of("strategy", "compact", "summaryPlacement", "user_message"), 0.9);

        assertThat(sent.instructions()).isEqualTo("You help.");
        assertThat(sent.messages().get(0).type()).isEqualTo(TurnMessage.Type.USER);
        assertThat(sent.messages().get(0).text()).endsWith("SUMMARY 1");
        assertThat(texts(sent).subList(1, 2)).containsExactly("third question .....");
        assertThat(sent.messages()).hasSize(2);
    }

    @Test
    void an_old_tool_result_is_shown_as_a_note_and_stays_one_once_it_was() {
        List<TurnMessage> withResult = List.of(
                said("m1", TurnMessage.Type.USER, "list the files"),
                new TurnMessage("m2", "t", TurnMessage.Type.TOOL_RESULT,
                        List.of(new ContentPart.Text("x".repeat(200))), List.of(), Map.of("callId", "c1")),
                said("m3", TurnMessage.Type.USER, "and now?"));

        ModelRequest overBudget = sent(Map.of("strategy", "window", "keepRecentToolResults", 0), 0.9, withResult);

        assertThat(overBudget.messages().get(1).text()).startsWith("[Tool result removed")
                .as("the note names the result, for whoever reads it again").contains("id=m2");
        assertThat(remembered).containsOnlyKeys("m2");

        // The store hands the message back with the note; shown is the note, however much room there is.
        List<TurnMessage> marked = List.of(withResult.get(0),
                new TurnMessage("m2", "t", TurnMessage.Type.TOOL_RESULT, List.of(new ContentPart.Text("short")),
                        List.of(), Map.of(SessionStore.COMPRESSED, remembered.get("m2"))),
                withResult.get(2));
        assertThat(sent(Map.of("strategy", "window"), 1.0, marked).messages().get(1).text())
                .isEqualTo(remembered.get("m2"));
    }

    /** Three turns: two old results and a skill's in the first, one result in the second, the third underway. */
    private static final List<TurnMessage> WITH_OLD_RESULTS = List.of(
            said("m1", TurnMessage.Type.USER, "read both files"),
            result("m2", "file_read", "a".repeat(15_000)),
            result("m3", "file_read", "b".repeat(15_000)),
            result("m4", "skill", "s".repeat(15_000)),
            said("m5", TurnMessage.Type.USER, "and the third"),
            result("m6", "file_read", "c".repeat(15_000)),
            said("m7", TurnMessage.Type.USER, "now compare them"));

    @Test
    void pruning_after_the_turn_notes_old_results_although_the_history_fits() {
        ModelRequest sent = sent(Map.of("strategy", "window", "toolResultPruning", "after_turn",
                "keepRecentToolTokens", 1_000), 100_000, WITH_OLD_RESULTS);

        assertThat(sent.messages().get(1).text()).startsWith("[Tool result removed").contains("id=m2");
        assertThat(sent.messages().get(2).text()).startsWith("[Tool result removed").contains("id=m3");
        assertThat(sent.messages().get(3).text()).as("a skill is instructions, not a result").startsWith("sss");
        assertThat(sent.messages().get(5).text()).as("the turn before the one underway is left alone")
                .startsWith("ccc");
        assertThat(remembered).containsOnlyKeys("m2", "m3");
    }

    @Test
    void pruning_after_the_turn_keeps_the_newest_results_within_its_budget_and_what_is_not_worth_it() {
        ModelRequest oneKept = sent(Map.of("strategy", "window", "toolResultPruning", "after_turn",
                "keepRecentToolTokens", 15_000), 100_000, WITH_OLD_RESULTS);

        // The newer of the two fits the budget; the older alone frees too little to change the history for.
        assertThat(texts(oneKept)).noneMatch(text -> text.startsWith("[Tool result removed"));
        assertThat(remembered).isEmpty();
    }

    @Test
    void pruning_when_full_and_off_leave_results_of_a_history_that_fits() {
        for (String pruning : List.of("when_full", "off")) {
            ModelRequest sent = sent(Map.of("strategy", "window", "toolResultPruning", pruning),
                    100_000, WITH_OLD_RESULTS);
            assertThat(sent.messages()).as(pruning).isEqualTo(WITH_OLD_RESULTS);
        }
    }

    @Test
    void pruning_off_notes_nothing_also_when_the_history_does_not_fit() {
        ModelRequest sent = sent(Map.of("strategy", "window", "toolResultPruning", "off"), 500, WITH_OLD_RESULTS);

        assertThat(texts(sent)).noneMatch(text -> text.startsWith("[Tool result removed"));
        assertThat(remembered).isEmpty();
    }

    @Test
    void a_setting_nothing_reads_is_refused_when_it_is_saved() {
        assertThatThrownBy(() -> MemoryPlugin.check(Map.of("kind", "auto_compact")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'kind' is no memory setting");
        assertThatThrownBy(() -> MemoryPlugin.check(Map.of("toolResultPruning", "sometimes")))
                .isInstanceOf(IllegalArgumentException.class);
        MemoryPlugin.check(Map.of("strategy", "compact", "toolResultPruning", "after_turn"));
    }

    @Test
    void a_history_that_fits_is_sent_as_it_is() {
        assertThat(sent(Map.of("strategy", "compact"), 1.0).messages()).isEqualTo(HISTORY);
        assertThat(summaryRequests).isEmpty();
    }

    /** What the model is sent when an agent with these memory settings asks with the history. */
    private ModelRequest sent(Map<String, Object> memory, double ratio) {
        return sent(memory, ratio, HISTORY);
    }

    private ModelRequest sent(Map<String, Object> memory, double ratio, List<TurnMessage> history) {
        Map<String, Object> settings = new HashMap<>(memory);
        settings.put("maxConversationRatio", ratio);
        AgentDefinition agent = new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.NONE,
                List.of(), 5, Map.of(MemoryPlugin.MEMORY, settings));
        List<ModelRequest> sent = new ArrayList<>();
        registry.run(RuntimeHook.MODEL_CALL,
                new ModelCall(new AgentContext(SESSION, agent, TurnId.of("t3")),
                        new ModelRequest(TurnId.of("t3"), ModelSettings.NONE, "You help.", history, List.of())),
                call -> {
                    sent.add(call.request());
                    return LlmAnswer.of(List.of(), new Usage(0, 0));
                });
        return sent.get(0);
    }

    private static List<String> texts(ModelRequest request) {
        return request.messages().stream().map(TurnMessage::text).toList();
    }

    private static TurnMessage result(String id, String toolName, String text) {
        return new TurnMessage(id, "t", TurnMessage.Type.TOOL_RESULT, List.of(new ContentPart.Text(text)), List.of(),
                Map.of("callId", "c-" + id, "toolName", toolName));
    }

    private static TurnMessage said(String id, TurnMessage.Type type, String text) {
        return new TurnMessage(id, "t", type, List.of(new ContentPart.Text(text)), List.of(), Map.of());
    }
}
