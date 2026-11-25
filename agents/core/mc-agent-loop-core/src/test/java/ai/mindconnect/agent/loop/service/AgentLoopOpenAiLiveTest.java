package ai.mindconnect.agent.loop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.ToolResult;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.port.in.AgentLoop;
import ai.mindconnect.agent.loop.port.in.TurnContext;
import ai.mindconnect.agent.loop.port.out.AgentRoundToolExecutor;
import ai.mindconnect.agent.loop.port.out.TurnJournal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop against a real model: what the scripted tests assume about a
 * model — it requests a call, reads the result, reacts to a user message in
 * between — holds for one. Talks to the real OpenAI API; opt in with
 * {@code TEST_OPENAI=true} plus a key in {@code mc.env}.
 */
@EnabledIf("openAiEnabled")
class AgentLoopOpenAiLiveTest {

    private static final String SESSION = "session-1";

    private static final Map<String, Object> WEATHER_TOOL = Map.of(
            "type", "function",
            "name", "get_weather",
            "description", "Current weather for a city",
            "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of("city", Map.of("type", "string")),
                    "required", List.of("city")));

    static boolean openAiEnabled() {
        return TestOpenAi.enabled();
    }

    // ── fakes for everything that is not the model ──────────────────────────

    private static final class InMemoryJournal implements TurnJournal {
        private final List<TurnMessage> messages = new ArrayList<>();

        @Override public List<TurnMessage> load() {
            return List.copyOf(messages);
        }

        @Override public List<TurnMessage> append(List<TurnMessage> added) {
            List<TurnMessage> stored = new ArrayList<>();
            for (TurnMessage message : added) {
                stored.add(message.stored("m" + (messages.size() + stored.size() + 1)));
            }
            messages.addAll(stored);
            return stored;
        }

        @Override public boolean cancelRequested() {
            return false;
        }

        List<TurnMessage.Type> types() {
            return messages.stream().map(TurnMessage::type).toList();
        }
    }

    /** Runs a tool at once, in the caller's thread; the next round collects the result. */
    private static final class ImmediateExecutor implements AgentRoundToolExecutor {
        private final Map<String, Function<Map<String, Object>, String>> tools;
        private final Map<String, ToolResult> results = new HashMap<>();
        private final List<ToolCalls.Call> executed = new ArrayList<>();
        private Runnable whileExecuting = () -> { };

        ImmediateExecutor(Map<String, Function<Map<String, Object>, String>> tools) {
            this.tools = tools;
        }

        @Override public void execute(String requestId, String sessionId, ToolCalls.Call call) {
            executed.add(call);
            whileExecuting.run();
            results.put(call.callId(), ToolResult.ok(tools.get(call.name()).apply(call.arguments())));
        }

        @Override public ToolResult result(String sessionId, String callId) {
            return results.getOrDefault(callId, new ToolResult.Lost("unknown callId"));
        }

        @Override public void cancel(String requestId) {
            // every tool has finished before execute returns
        }
    }

    private final InMemoryJournal log = new InMemoryJournal();
    private final List<TurnMessage> inbox = new ArrayList<>();
    private final List<TurnStreamEvent> events = new ArrayList<>();
    private final ImmediateExecutor executor = new ImmediateExecutor(Map.of(
            "get_weather", arguments -> "22 degrees and sunny in " + arguments.get("city")));

    private static TurnMessage user(String text) {
        return TurnMessage.user(List.of(new ContentPart.Text(text)));
    }

    private List<TurnMessage> drainInbox() {
        List<TurnMessage> said = List.copyOf(inbox);
        inbox.clear();
        return said;
    }

    private TurnOutcome ask(String question) {
        return loop().start(turn(), user(question));
    }

    private AgentLoop loop() {
        return new DefaultAgentLoop(
                new DefaultAgentRound(
                        new OpenAiResponsesLlmProvider(TestOpenAi.apiKey(), TestOpenAi.model(),
                                "Answer with one short sentence.", List.of(WEATHER_TOOL)),
                        executor),
                5);
    }

    private TurnContext turn() {
        return new TurnContext("req1", SESSION, log, events::add, this::drainInbox, List.of());
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    void a_real_model_answers_a_plain_question_in_one_round() {
        TurnOutcome outcome = ask("What is the capital of France?");

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.text()).containsIgnoringCase("Paris");
        assertThat(outcome.usage().totalTokens()).isPositive();
        assertThat(log.messages.get(1).usage()).contains(outcome.usage());
        assertThat(executor.executed).isEmpty();
        assertThat(events.get(0)).isEqualTo(new TurnStreamEvent.AskingLlm(false));
        assertThat(events.get(events.size() - 1)).isEqualTo(new TurnStreamEvent.TurnUsage(
                outcome.usage().inputTokens(), outcome.usage().outputTokens()));
    }

    @Test
    void a_real_model_calls_a_tool_and_answers_from_its_result() {
        TurnOutcome outcome = ask("What is the weather in Lisbon? Use the tool.");

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.text()).containsIgnoringCase("sunny");
        assertThat(executor.executed).hasSize(1);
        assertThat(executor.executed.get(0).name()).isEqualTo("get_weather");
        assertThat(String.valueOf(executor.executed.get(0).arguments().get("city"))).containsIgnoringCase("Lisbon");
        assertThat(log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL,
                TurnMessage.Type.TOOL_DISPATCHED, TurnMessage.Type.TOOL_RESULT, TurnMessage.Type.ASSISTANT);
    }

    @Test
    void a_real_model_follows_a_steering_message_sent_while_its_tool_runs() {
        executor.whileExecuting = () -> inbox.add(user("Answer in German, and end your answer with the word FERTIG."));

        TurnOutcome outcome = ask("What is the weather in Lisbon? Use the tool.");

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.text()).containsIgnoringCase("FERTIG");
        assertThat(log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL,
                TurnMessage.Type.TOOL_DISPATCHED, TurnMessage.Type.TOOL_RESULT,
                TurnMessage.Type.USER, TurnMessage.Type.ASSISTANT);
    }

    @Test
    void a_cancel_aborts_the_call_to_a_real_model() throws InterruptedException {
        AgentLoop loop = loop();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loop.cancel("req1");
        });

        long startedAt = System.nanoTime();
        TurnOutcome outcome = loop.start(turn(),
                user("Write a detailed essay of two thousand words about the history of Lisbon."));
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;
        canceller.join();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
        assertThat(tookMs).isLessThan(10_000);        // the essay alone takes far longer
        assertThat(log.types()).containsExactly(TurnMessage.Type.USER);
    }
}
