package ai.mindconnect.agent.loop.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.RoundOutcome;
import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.ToolResult;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.domain.TurnProgress;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.in.AgentLoop;
import ai.mindconnect.agent.loop.port.in.TurnContext;
import ai.mindconnect.agent.loop.port.out.AgentRoundAdvisor;
import ai.mindconnect.agent.loop.port.out.AgentRoundToolExecutor;
import ai.mindconnect.agent.loop.port.out.LlmProvider;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.loop.port.out.TurnJournal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop against a scripted model and a hand-steered executor — no queue,
 * no threads: every wait surfaces as a {@link TurnOutcome} instead of
 * blocking, which is exactly what a task worker builds on.
 */
class AgentLoopTest {

    private static final String SESSION = "session-1";
    private static final String REQUEST = "req1";

    // ── fakes ───────────────────────────────────────────────────────────────

    /** The journal of one turn as a list — what a store adapter does, without the store. */
    private static class InMemoryJournal implements TurnJournal {
        final List<TurnMessage> messages = new ArrayList<>();
        /** The size of every append — one per step of the turn. */
        final List<Integer> appends = new ArrayList<>();
        boolean cancelRequested;

        /** What an earlier leg of the turn left behind. */
        TurnMessage seed(TurnMessage message) {
            return append(List.of(message.inTurn(REQUEST))).get(0);
        }

        @Override public List<TurnMessage> load() {
            return List.copyOf(messages);
        }

        @Override public List<TurnMessage> append(List<TurnMessage> added) {
            appends.add(added.size());
            List<TurnMessage> stored = new ArrayList<>();
            for (TurnMessage message : added) {
                stored.add(message.stored("m" + (messages.size() + stored.size() + 1)));
            }
            messages.addAll(stored);
            return stored;
        }

        @Override public boolean cancelRequested() {
            return cancelRequested;
        }

        List<TurnMessage.Type> types() {
            return messages.stream().map(TurnMessage::type).toList();
        }

        TurnMessage last() {
            return messages.get(messages.size() - 1);
        }
    }

    /** Answers from a script, one entry per model call. */
    private static final class ScriptedLlm implements LlmProvider {
        private final Deque<LlmAnswer> script = new ArrayDeque<>();
        private final List<List<TurnMessage>> askedWith = new ArrayList<>();
        /** Runs while the model "answers" — where a test lets the user interrupt. */
        private Runnable whileAnswering = () -> { };
        private final List<String> cancelled = new ArrayList<>();
        int calls;

        ScriptedLlm answer(TurnMessage... messages) {
            script.add(LlmAnswer.of(List.of(messages), new Usage(10, 5)));
            return this;
        }

        @Override public LlmAnswer ask(String requestId, String sessionId, List<TurnMessage> history,
                                       TurnEventListener events) {
            calls++;
            askedWith.add(List.copyOf(history));
            if (script.isEmpty()) throw new AssertionError("Model asked more often than scripted");
            whileAnswering.run();
            LlmAnswer answer = script.pop();
            // a streaming model: the answer text goes out word by word first
            for (TurnMessage message : answer.messages()) {
                for (String word : message.text().split(" ")) {
                    if (!word.isEmpty()) events.onEvent(new TurnStreamEvent.TextDelta(word));
                }
            }
            return answer;
        }

        @Override public void cancel(String requestId) {
            cancelled.add(requestId);
        }
    }

    /** Executor whose results the test sets by hand — "the tool finished now". */
    private static final class HandSteeredExecutor implements AgentRoundToolExecutor {
        private final Map<String, ToolResult> results = new HashMap<>();
        private final List<String> executed = new ArrayList<>();
        private final List<String> cancelled = new ArrayList<>();

        void finishes(String callId, String output) {
            results.put(callId, ToolResult.ok(output));
        }

        void stillRunning(String callId) {
            results.put(callId, new ToolResult.Running());
        }

        @Override public void execute(String requestId, String sessionId, ToolCalls.Call call) {
            executed.add(call.callId());
            results.putIfAbsent(call.callId(), new ToolResult.Running());
        }

        @Override public ToolResult result(String sessionId, String callId) {
            return results.getOrDefault(callId, new ToolResult.Lost("unknown callId"));
        }

        @Override public void cancel(String requestId) {
            cancelled.add(requestId);
        }
    }

    private static TurnMessage userMessage(String text) {
        return TurnMessage.user(List.of(new ContentPart.Text(text)));
    }

    private static TurnMessage modelToolCall(String callId, String name) {
        return TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall(callId, name, Map.of())));
    }

    private record Fixture(InMemoryJournal log, ScriptedLlm llm, HandSteeredExecutor executor,
                           List<TurnStreamEvent> events, List<TurnMessage> inbox) {

        static Fixture create() {
            return new Fixture(new InMemoryJournal(), new ScriptedLlm(), new HandSteeredExecutor(), new ArrayList<>(),
                    new ArrayList<>());
        }

        /** The user says something while the turn runs. */
        void steer(String text) {
            inbox.add(userMessage(text));
        }

        List<TurnMessage> drainInbox() {
            List<TurnMessage> said = List.copyOf(inbox);
            inbox.clear();
            return said;
        }

        /** A loop as a caller holds it: one for every turn. */
        AgentLoop loop(int maxRounds) {
            return new DefaultAgentLoop(new DefaultAgentRound(llm, executor), maxRounds);
        }

        /** The context a caller builds, the same for the start and for every resume. */
        TurnContext turn(AgentRoundAdvisor... advisors) {
            return new TurnContext(REQUEST, SESSION, log, events::add, this::drainInbox, List.of(advisors));
        }

        /** The stored messages the loop announced, in order. */
        List<TurnMessage> published() {
            return events.stream()
                    .filter(TurnStreamEvent.MessageWritten.class::isInstance)
                    .map(event -> ((TurnStreamEvent.MessageWritten) event).message())
                    .toList();
        }

        TurnOutcome start(AgentRoundAdvisor... advisors) {
            return loop(10).start(turn(advisors), userMessage("do it"));
        }

        /** A resume as another worker would do it: a new loop, nothing carried over. */
        TurnOutcome resume() {
            return loop(10).resume(turn());
        }
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    void a_plain_answer_completes_in_one_round() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("hello"));

        TurnOutcome outcome = f.start();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.text()).isEqualTo("hello");
        assertThat(outcome.usage().totalTokens()).isEqualTo(15);
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.ASSISTANT);
    }

    @Test
    void a_tool_call_runs_dispatch_collect_answer_in_this_order() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "search"))
             .answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found it");

        TurnOutcome outcome = f.start();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.executor.executed).containsExactly("c1");
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL,
                TurnMessage.Type.TOOL_DISPATCHED, TurnMessage.Type.TOOL_RESULT, TurnMessage.Type.ASSISTANT);
    }

    @Test
    void a_tool_result_carries_the_plain_output_and_the_pairing_metadata() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "search"))
             .answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found it");

        f.start();

        TurnMessage result = f.log.messages.stream()
                .filter(m -> m.type() == TurnMessage.Type.TOOL_RESULT).findFirst().orElseThrow();
        assertThat(result.text()).isEqualTo("found it");
        assertThat(result.metadata())
                .containsEntry("callId", "c1")
                .containsEntry("toolName", "search")
                .containsEntry("failed", false);
    }

    @Test
    void every_appended_message_is_published_as_the_log_stored_it() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "search"))
             .answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found it");

        f.start();

        // save first, then publish: what is announced carries the store's id
        assertThat(f.published()).hasSize(5);
        assertThat(f.published()).allSatisfy(m -> assertThat(m.id()).isNotNull());
        assertThat(f.published()).containsExactlyElementsOf(f.log.messages);
    }

    @Test
    void the_model_is_asked_with_the_media_parts_of_the_user_message() {
        Fixture f = Fixture.create();
        ContentPart image = ContentPart.Image.of(new ContentPart.MediaSource.FileId("file-1"));
        f.llm.answer(TurnMessage.assistant("a cat"));

        TurnOutcome outcome = f.loop(10).start(f.turn(),
                TurnMessage.user(List.of(new ContentPart.Text("what is this?"), image)));

        assertThat(outcome.text()).isEqualTo("a cat");
        assertThat(f.llm.askedWith).hasSize(1);
        assertThat(f.llm.askedWith.get(0).get(0).content())
                .containsExactly(new ContentPart.Text("what is this?"), image);
    }

    @Test
    void a_running_tool_surfaces_as_waiting_for_tools_instead_of_blocking() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");

        TurnOutcome outcome = f.start();

        assertThat(outcome.waitsForTools()).isTrue();
        assertThat(outcome.waitingFor()).containsExactly("c1");
    }

    @Test
    void a_resumed_turn_collects_the_result_without_reasking_the_model() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        assertThat(f.start().waitsForTools()).isTrue();

        // ...the tool finishes while the turn holds no thread...
        f.executor.finishes("c1", "late result");
        f.llm.answer(TurnMessage.assistant("done"));

        TurnOutcome outcome = f.resume();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.executor.executed).containsExactly("c1");     // dispatched ONCE
        assertThat(f.llm.calls).isEqualTo(2);                      // no re-ask for the tool round
    }

    @Test
    void the_round_brake_counts_across_resumes_and_closes_the_refused_call() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "a")).answer(modelToolCall("c2", "b")).answer(modelToolCall("c3", "c"));
        f.executor.stillRunning("c1");
        f.executor.finishes("c2", "ok");
        assertThat(f.loop(2).start(f.turn(), userMessage("do it")).waitsForTools()).isTrue();

        // Another loop goes on, told nothing: the first round is in the
        // journal, so the cap of 2 lets one more request run and refuses
        // the third.
        f.executor.finishes("c1", "ok");
        TurnOutcome outcome = f.loop(2).resume(f.turn());

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.INCOMPLETE);
        assertThat(outcome.incompleteReason()).isEqualTo(TurnOutcome.IncompleteReason.MAX_ROUNDS);
        assertThat(outcome.rounds()).isEqualTo(3);
        assertThat(f.executor.executed).containsExactly("c1", "c2");
        assertThat(f.log.last().type()).isEqualTo(TurnMessage.Type.TOOL_RESULT);
        assertThat(f.log.last().text()).contains("round cap of 2");
        assertThat(ToolCalls.of(ToolCalls.episode(f.log.load())).allDone()).isTrue();
    }

    @Test
    void a_cancel_marked_in_the_journal_ends_the_turn_before_the_model_is_asked() {
        // A turn that waits for a tool holds no thread: the cancel is marked
        // while nothing runs and must reach whoever goes on, wherever.
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        f.start();
        f.log.cancelRequested = true;
        f.executor.finishes("c1", "late result");

        TurnOutcome outcome = f.resume();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
        assertThat(f.llm.calls).isEqualTo(1);
        assertThat(outcome.rounds()).isEqualTo(1);
    }

    @Test
    void a_cancel_for_a_turn_that_is_not_running_here_reaches_nobody() {
        Fixture f = Fixture.create();
        AgentLoop loop = f.loop(10);
        f.llm.answer(TurnMessage.assistant("hello"));
        loop.cancel(REQUEST);

        assertThat(loop.start(f.turn(), userMessage("do it")).status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.llm.cancelled).isEmpty();
    }

    @Test
    void a_cancel_during_the_model_call_reaches_the_model_and_ends_the_turn_cancelled() {
        Fixture f = Fixture.create();
        AgentLoop loop = f.loop(10);
        f.llm.answer(TurnMessage.assistant("half an ans"));
        f.llm.whileAnswering = () -> loop.cancel("req1");

        TurnOutcome outcome = loop.start(f.turn(), userMessage("do it"));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
        assertThat(f.llm.cancelled).containsExactly("req1");
        assertThat(f.executor.cancelled).containsExactly("req1");
        // what the model had said until then stays in the conversation
        assertThat(f.log.last().text()).isEqualTo("half an ans");
        assertThat(outcome.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void a_cancel_between_rounds_starts_no_further_round() {
        Fixture f = Fixture.create();
        AgentLoop loop = f.loop(10);
        f.llm.answer(modelToolCall("c1", "search"));
        f.llm.whileAnswering = () -> loop.cancel("req1");

        TurnOutcome outcome = loop.start(f.turn(), userMessage("do it"));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
        assertThat(f.executor.executed).isEmpty();          // the requested call never started
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL);
    }

    @Test
    void a_cancel_of_one_request_does_not_touch_another() {
        Fixture f = Fixture.create();
        AgentLoop loop = f.loop(10);
        f.llm.answer(TurnMessage.assistant("hello"));
        loop.cancel("another request");

        assertThat(loop.start(f.turn(), userMessage("do it")).status()).isEqualTo(TurnOutcome.Status.COMPLETED);
    }

    @Test
    void a_cancel_is_forgotten_once_its_call_has_ended() {
        Fixture f = Fixture.create();
        AgentLoop loop = f.loop(10);
        f.llm.answer(TurnMessage.assistant("half an ans")).answer(TurnMessage.assistant("hello"));
        f.llm.whileAnswering = () -> {
            if (f.llm.calls == 1) loop.cancel(REQUEST);
        };
        assertThat(loop.start(f.turn(), userMessage("do it")).status()).isEqualTo(TurnOutcome.Status.CANCELLED);

        assertThat(loop.resume(f.turn()).status()).isEqualTo(TurnOutcome.Status.COMPLETED);
    }

    @Test
    void a_truncated_answer_ends_the_turn_incomplete() {
        Fixture f = Fixture.create();
        f.llm.script.add(new LlmAnswer(List.of(TurnMessage.assistant("half an ans")), new Usage(10, 5), true));

        TurnOutcome outcome = f.start();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.INCOMPLETE);
        assertThat(outcome.incompleteReason()).isEqualTo(TurnOutcome.IncompleteReason.MAX_OUTPUT_TOKENS);
    }

    // ── start, resume and the events ────────────────────────────────────────

    @Test
    void start_appends_the_user_message_before_the_model_is_asked() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("hello"));

        f.start();

        assertThat(f.log.messages.get(0).type()).isEqualTo(TurnMessage.Type.USER);
        assertThat(f.log.messages.get(0).text()).isEqualTo("do it");
        assertThat(f.llm.askedWith.get(0)).containsExactly(f.log.messages.get(0));
    }

    @Test
    void resume_appends_nothing_before_it_goes_on() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        f.start();
        int stored = f.log.messages.size();

        assertThat(f.resume().waitsForTools()).isTrue();

        assertThat(f.log.messages).hasSize(stored);
    }

    @Test
    void a_turn_reports_its_start_the_answer_as_it_arrives_each_stored_message_and_its_end() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("hello there"));

        TurnOutcome outcome = f.start();

        assertThat(f.events).containsExactly(
                new TurnStreamEvent.MessageWritten(f.log.messages.get(0)),
                new TurnStreamEvent.AskingLlm(),
                new TurnStreamEvent.TextDelta("hello"),
                new TurnStreamEvent.TextDelta("there"),
                new TurnStreamEvent.MessageWritten(f.log.messages.get(1)),
                usageOf(outcome));
    }

    @Test
    void a_turn_that_waits_reports_waiting_and_its_resume_reports_resumed() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        f.start();

        // A turn that waits has not left: the call it asked for is told, its cost is not yet.
        assertThat(f.events).contains(new TurnStreamEvent.ToolCallStarted("c1", "slow", Map.of(), 0));
        assertThat(f.events).noneMatch(TurnStreamEvent.TurnUsage.class::isInstance);

        // A resume that finds the call still running asks nobody, and says nothing.
        f.events.clear();
        f.resume();
        assertThat(f.events).isEmpty();

        f.events.clear();
        f.executor.finishes("c1", "late result");
        f.llm.answer(TurnMessage.assistant("done"));
        TurnOutcome outcome = f.resume();

        // The model is asked again only now that the call has answered.
        assertThat(f.events).filteredOn(TurnStreamEvent.AskingLlm.class::isInstance).hasSize(1);
        assertThat(f.events.get(f.events.size() - 1)).isEqualTo(usageOf(outcome));
    }

    @Test
    void a_cancelled_turn_still_reports_its_end() {
        Fixture f = Fixture.create();
        f.log.cancelRequested = true;

        TurnOutcome outcome = f.start();

        assertThat(f.events.get(f.events.size() - 1)).isEqualTo(usageOf(outcome));
        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
    }

    // ── the journal is the turn's memory ────────────────────────────────────

    @Test
    void a_resumed_turn_reports_the_rounds_and_the_cost_of_all_its_legs() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        TurnOutcome waiting = f.start();
        assertThat(waiting.rounds()).isEqualTo(1);
        assertThat(waiting.usage().totalTokens()).isEqualTo(15);

        f.executor.finishes("c1", "late result");
        f.llm.answer(TurnMessage.assistant("done"));
        TurnOutcome outcome = f.resume();

        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(outcome.usage()).isEqualTo(new Usage(20, 10));
    }

    @Test
    void resuming_twice_counts_nothing_twice() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        f.start();

        TurnOutcome first = f.resume();
        TurnOutcome second = f.resume();

        assertThat(second.rounds()).isEqualTo(first.rounds()).isEqualTo(1);
        assertThat(second.usage()).isEqualTo(first.usage()).isEqualTo(new Usage(10, 5));
    }

    @Test
    void a_steering_message_does_not_reset_what_the_turn_has_counted() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("first answer")).answer(modelToolCall("c1", "slow"));
        f.llm.whileAnswering = () -> {
            if (f.llm.calls == 1) f.steer("one more thing");
        };
        f.executor.stillRunning("c1");
        assertThat(f.start().waitsForTools()).isTrue();

        // user, answer, steering message, tool call — still one turn
        assertThat(TurnProgress.of(ToolCalls.episode(f.log.load())).modelRounds()).isEqualTo(2);
        assertThat(f.resume().rounds()).isEqualTo(2);
    }

    @Test
    void an_answer_an_advisor_replaced_still_carries_what_the_round_cost() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("draft"));
        AgentRoundAdvisor reviewer = new AgentRoundAdvisor() {
            @Override public RoundOutcome aroundRound(RoundContext context, Execution execution) {
                RoundOutcome out = execution.proceed();
                return new RoundOutcome(List.of(TurnMessage.assistant("reviewed")),
                        out.outcome(), out.usage(), out.waitingFor());
            }
        };

        f.start(reviewer);

        assertThat(f.log.last().text()).isEqualTo("reviewed");
        assertThat(f.log.last().usage()).contains(new Usage(10, 5));
    }

    @Test
    void only_the_message_of_a_model_call_carries_a_cost() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "search")).answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found");

        f.start();

        assertThat(f.log.messages.stream().map(m -> m.usage().isPresent()))
                .containsExactly(false, true, false, false, true);
    }

    @Test
    void starting_the_same_request_again_does_not_ask_the_question_twice() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        f.start();

        // the worker repeats its task after a crash
        f.executor.finishes("c1", "late result");
        f.llm.answer(TurnMessage.assistant("done"));
        TurnOutcome outcome = f.start();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL,
                TurnMessage.Type.TOOL_DISPATCHED, TurnMessage.Type.TOOL_RESULT, TurnMessage.Type.ASSISTANT);
    }

    @Test
    void everything_the_loop_writes_belongs_to_the_turn_of_the_request() {
        Fixture f = Fixture.create();
        f.steer("be brief");
        f.llm.answer(modelToolCall("c1", "search")).answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found");

        f.start();

        assertThat(f.log.messages).hasSize(6).allSatisfy(m -> assertThat(m.turnId()).isEqualTo(REQUEST));
    }

    @Test
    void the_messages_of_one_step_reach_the_journal_in_one_append() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.toolCalls(List.of(
                        new TurnMessage.ToolCall("c1", "search", Map.of()),
                        new TurnMessage.ToolCall("c2", "fetch", Map.of()))))
                .answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found");
        f.executor.finishes("c2", "fetched");

        f.start();

        // question, request, both dispatch markers, both results, answer
        assertThat(f.log.appends).containsExactly(1, 1, 2, 2, 1);
    }

    @Test
    void a_journal_that_keeps_its_own_count_is_asked_instead_of_counted() {
        Fixture f = Fixture.create();
        InMemoryJournal counting = new InMemoryJournal() {
            @Override public TurnProgress progress() {
                return new TurnProgress(7, new Usage(700, 70));
            }
        };
        f.llm.answer(TurnMessage.assistant("hello"));
        TurnContext turn = new TurnContext(REQUEST, SESSION, counting, event -> { }, List::of, List.of());

        TurnOutcome outcome = f.loop(10).start(turn, userMessage("do it"));

        assertThat(outcome.rounds()).isEqualTo(8);
        assertThat(outcome.usage()).isEqualTo(new Usage(710, 75));
    }

    @Test
    void one_loop_serves_two_turns_and_keeps_them_apart() {
        Fixture first = Fixture.create();
        Fixture second = Fixture.create();
        ScriptedLlm llm = new ScriptedLlm()
                .answer(TurnMessage.assistant("to the first")).answer(TurnMessage.assistant("to the second"));
        AgentLoop loop = new DefaultAgentLoop(new DefaultAgentRound(llm, new HandSteeredExecutor()), 10);
        TurnContext other = new TurnContext("req2", "session-2", second.log, second.events::add,
                List::of, List.of());

        TurnOutcome one = loop.start(first.turn(), userMessage("first question"));
        TurnOutcome two = loop.start(other, userMessage("second question"));

        assertThat(one.text()).isEqualTo("to the first");
        assertThat(two.text()).isEqualTo("to the second");
        assertThat(first.log.messages).extracting(TurnMessage::turnId).containsOnly(REQUEST);
        assertThat(second.log.messages).extracting(TurnMessage::turnId).containsOnly("req2");
        // An event names no request: each turn's listener hears its own turn and nothing of the other.
        assertThat(second.published()).containsExactlyElementsOf(second.log.messages);
        assertThat(first.published()).containsExactlyElementsOf(first.log.messages);
    }

    // ── steering ────────────────────────────────────────────────────────────

    @Test
    void a_steering_message_lands_after_the_tool_results_and_before_the_next_model_call() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        assertThat(f.start().waitsForTools()).isTrue();

        // ...the user speaks while the tool still runs...
        f.steer("stop, use the other file");
        assertThat(f.resume().waitsForTools()).isTrue();
        assertThat(f.inbox).hasSize(1);                 // not delivered into an open call

        f.executor.finishes("c1", "late result");
        f.llm.answer(TurnMessage.assistant("ok, the other file"));
        TurnOutcome outcome = f.resume();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL,
                TurnMessage.Type.TOOL_DISPATCHED, TurnMessage.Type.TOOL_RESULT,
                TurnMessage.Type.USER, TurnMessage.Type.ASSISTANT);
        List<TurnMessage> secondWindow = f.llm.askedWith.get(1);
        assertThat(secondWindow.get(secondWindow.size() - 1).text()).isEqualTo("stop, use the other file");
        assertThat(f.inbox).isEmpty();
    }

    @Test
    void a_steering_message_sent_during_the_final_answer_keeps_the_turn_going() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("first answer")).answer(TurnMessage.assistant("second answer"));
        f.llm.whileAnswering = () -> {
            if (f.llm.calls == 1) f.steer("one more thing");
        };

        TurnOutcome outcome = f.start();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.text()).isEqualTo("second answer");
        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(outcome.usage().totalTokens()).isEqualTo(30);
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.ASSISTANT,
                TurnMessage.Type.USER, TurnMessage.Type.ASSISTANT);
    }

    @Test
    void a_steering_message_is_stored_and_published_like_any_other() {
        Fixture f = Fixture.create();
        f.steer("be brief");
        f.llm.answer(TurnMessage.assistant("ok"));

        f.start();

        TurnMessage steered = f.log.messages.get(1);
        assertThat(steered.text()).isEqualTo("be brief");
        assertThat(steered.id()).isNotNull();
        assertThat(f.published()).containsExactly(f.log.messages.get(0), steered, f.log.last());
    }

    @Test
    void a_steering_message_does_not_reopen_the_calls_before_it() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "search")).answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found");
        f.llm.whileAnswering = () -> {
            if (f.llm.calls == 1) f.steer("and hurry");
        };

        f.start();

        assertThat(f.executor.executed).containsExactly("c1");
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL,
                TurnMessage.Type.TOOL_DISPATCHED, TurnMessage.Type.TOOL_RESULT,
                TurnMessage.Type.USER, TurnMessage.Type.ASSISTANT);
    }

    @Test
    void what_is_still_in_the_inbox_when_the_turn_is_cancelled_stays_there() {
        Fixture f = Fixture.create();
        f.steer("too late");
        f.log.cancelRequested = true;

        TurnOutcome outcome = f.start();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
        assertThat(f.inbox).hasSize(1);
        assertThat(f.log.types()).containsExactly(TurnMessage.Type.USER);
    }

    @Test
    void an_advisor_rewrites_the_answer_before_it_is_persisted() {
        // The reviewer chain as an advisor: the conversation must only ever
        // hold the reviewed text — and the outcome reports the same.
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("draft"));
        AgentRoundAdvisor reviewer = new AgentRoundAdvisor() {
            @Override public RoundOutcome aroundRound(RoundContext context, Execution execution) {
                RoundOutcome out = execution.proceed();
                if (out.outcome() != RoundOutcome.Outcome.ANSWERED) return out;
                List<TurnMessage> reviewed = out.added().stream()
                        .map(m -> m.type() == TurnMessage.Type.ASSISTANT ? TurnMessage.assistant("reviewed") : m)
                        .toList();
                return new RoundOutcome(reviewed, out.outcome(), out.usage(), out.waitingFor());
            }
        };

        TurnOutcome outcome = f.start(reviewer);

        assertThat(outcome.text()).isEqualTo("reviewed");
        assertThat(f.log.last().text()).isEqualTo("reviewed");     // never "draft"
    }

    @Test
    void an_after_round_advisor_sees_each_rounds_persisted_messages() {
        Fixture f = Fixture.create();
        f.llm.answer(modelToolCall("c1", "search")).answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found");
        List<List<TurnMessage.Type>> rounds = new ArrayList<>();
        AgentRoundAdvisor observer = new AgentRoundAdvisor() {
            @Override public void afterRoundPersisted(RoundContext context, List<TurnMessage> persisted) {
                rounds.add(persisted.stream().map(TurnMessage::type).toList());
            }
        };

        f.start(observer);

        // one call per round, each with exactly that round's durable increment
        assertThat(rounds).containsExactly(
                List.of(TurnMessage.Type.TOOL_CALL),
                List.of(TurnMessage.Type.TOOL_DISPATCHED),
                List.of(TurnMessage.Type.TOOL_RESULT),
                List.of(TurnMessage.Type.ASSISTANT));
    }

    @Test
    void a_throwing_after_round_advisor_does_not_cost_the_turn() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("hello"));
        AgentRoundAdvisor broken = new AgentRoundAdvisor() {
            @Override public void afterRoundPersisted(RoundContext context, List<TurnMessage> persisted) {
                throw new IllegalStateException("boom");
            }
        };

        assertThat(f.start(broken).status()).isEqualTo(TurnOutcome.Status.COMPLETED);
    }

    @Test
    void a_throwing_listener_does_not_cost_the_turn() {
        Fixture f = Fixture.create();
        f.llm.answer(TurnMessage.assistant("hello"));
        TurnContext watched = new TurnContext(REQUEST, SESSION, f.log,
                event -> { throw new IllegalStateException("watcher broke"); }, List::of, List.of());

        TurnOutcome outcome = f.loop(10).start(watched, userMessage("do it"));

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.log.last().text()).isEqualTo("hello");
    }

    @Test
    void a_lost_call_fails_instead_of_waiting_forever() {
        // Executor restarted, callId gone: Lost ≠ Running — the round must
        // close the call with a readable error, not spin on it.
        Fixture f = Fixture.create();
        f.log.seed(userMessage("do it"));
        f.log.seed(modelToolCall("c1", "search"));
        f.log.seed(TurnMessage.dispatched("c1"));
        f.llm.answer(TurnMessage.assistant("sorry, that broke"));

        TurnOutcome outcome = f.resume();

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        TurnMessage result = f.log.messages.stream()
                .filter(m -> m.type() == TurnMessage.Type.TOOL_RESULT).findFirst().orElseThrow();
        assertThat(result.metadata()).containsEntry("failed", true);
        assertThat(result.text()).contains("interrupted before completion");
    }

    private static TurnStreamEvent usageOf(TurnOutcome outcome) {
        return new TurnStreamEvent.TurnUsage(outcome.usage().inputTokens(), outcome.usage().outputTokens());
    }
}
