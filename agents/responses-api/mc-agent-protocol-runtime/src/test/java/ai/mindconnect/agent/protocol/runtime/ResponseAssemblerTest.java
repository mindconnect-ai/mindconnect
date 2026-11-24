package ai.mindconnect.agent.protocol.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

/** The whole runtime→protocol translation, offline — no runtime, no LLM. */
class ResponseAssemblerTest {

    private static final String TURN = "turn-1";

    private final ResponseAssembler assembler =
            new ResponseAssembler("resp_1", "conv_1", "sess_1", "test-agent");

    private static TurnEvent called(String callId, String tool, Map<String, Object> arguments) {
        return new TurnEvent.MessageAppended(TURN,
                TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall(callId, tool, arguments))));
    }

    private static TurnEvent result(String callId, String tool, String text) {
        return new TurnEvent.MessageAppended(TURN, TurnMessage.toolResult(callId, tool, text, false));
    }

    private static TurnEvent done(Usage usage) {
        return new TurnEvent.TurnEnded(TURN, TurnOutcome.completed(usage, 1, ""));
    }

    @Test
    void fullTurn_tokensToolsAndAnswer() {
        assembler.accept(called("c1", "web_search", Map.of("q", "lisbon")));
        assembler.accept(result("c1", "web_search", "3 results"));
        assembler.accept(new TurnEvent.TextDelta(TURN, "It is "));
        assembler.accept(new TurnEvent.TextDelta(TURN, "sunny."));
        assembler.accept(done(Usage.ZERO));

        Response r = assembler.snapshot();

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).isEqualTo("It is sunny.");
        List<ConversationItem> items = r.output().stream().map(ConversationItemRecord::item).toList();
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).isInstanceOf(ConversationItem.FunctionCall.class);
        assertThat(items.get(1)).isInstanceOf(ConversationItem.FunctionCallOutput.class);
        // call and result are paired under the response's own id:
        assertThat(((ConversationItem.FunctionCall) items.get(0)).callId())
                .isEqualTo(((ConversationItem.FunctionCallOutput) items.get(1)).callId());
        assertThat(r.openFunctionCalls()).isEmpty();
    }

    @Test
    void thinkingBecomesAReasoningItemAheadOfTheAnswer() {
        List<ResponseEvent> events = new ArrayList<>();
        assembler.subscribe(0, events::add);
        assembler.accept(new TurnEvent.ReasoningDelta(TURN, "Let me "));
        assembler.accept(new TurnEvent.ReasoningDelta(TURN, "see."));
        assembler.accept(new TurnEvent.TextDelta(TURN, "42"));
        assembler.accept(done(Usage.ZERO));

        Response r = assembler.snapshot();
        List<ConversationItem> items = r.output().stream().map(ConversationItemRecord::item).toList();

        assertThat(items).hasSize(2);
        assertThat(items.get(0)).isEqualTo(new ConversationItem.Reasoning("Let me see.", null));
        assertThat(items.get(1)).isInstanceOf(ConversationItem.Message.class);
        assertThat(r.outputText()).isEqualTo("42");
        assertThat(events.stream().filter(e -> e instanceof ResponseEvent.ReasoningDelta)
                .map(e -> ((ResponseEvent.ReasoningDelta) e).delta()))
                .containsExactly("Let me ", "see.");
        // the reasoning item is done before the text item is added
        List<String> order = events.stream()
                .filter(e -> e instanceof ResponseEvent.OutputItemDone || e instanceof ResponseEvent.OutputItemAdded)
                .map(e -> e.getClass().getSimpleName()).toList();
        assertThat(order).containsExactly("OutputItemAdded", "OutputItemDone", "OutputItemAdded", "OutputItemDone");
    }

    @Test
    void reasoningMidAnswerClosesTheMessageFirst() {
        List<ResponseEvent> events = new ArrayList<>();
        assembler.subscribe(0, events::add);
        assembler.accept(new TurnEvent.TextDelta(TURN, "Sure. "));
        assembler.accept(new TurnEvent.ReasoningDelta(TURN, "wait"));
        assembler.accept(new TurnEvent.TextDelta(TURN, "42"));
        assembler.accept(done(Usage.ZERO));

        List<ConversationItemRecord> output = assembler.snapshot().output();
        assertThat(output).hasSize(3);
        assertThat(output.get(0).item()).isInstanceOf(ConversationItem.Message.class);
        assertThat(output.get(1).item()).isEqualTo(new ConversationItem.Reasoning("wait", null));
        assertThat(output.get(2).item()).isInstanceOf(ConversationItem.Message.class);
        // every item is announced and finished at one and the same index
        List<Long> added = events.stream().filter(e -> e instanceof ResponseEvent.OutputItemAdded)
                .map(e -> ((ResponseEvent.OutputItemAdded) e).entry().seq()).toList();
        List<Long> done = events.stream().filter(e -> e instanceof ResponseEvent.OutputItemDone)
                .map(e -> ((ResponseEvent.OutputItemDone) e).entry().seq()).toList();
        assertThat(added).containsExactly(1L, 2L, 3L);
        assertThat(done).isEqualTo(added);
    }

    @Test
    void subAgentBecomesAgentCallPair() {
        assembler.subAgentStarted("turn-2", "researcher", "sub-session");
        assembler.subAgentStarted("turn-2", "researcher", "sub-session");   // heard again with its next event
        assembler.subAgentEnded("turn-2", "X found");
        assembler.accept(new TurnEvent.TextDelta(TURN, "done"));
        assembler.accept(done(Usage.ZERO));

        List<ConversationItem> items = assembler.snapshot().output().stream().map(ConversationItemRecord::item).toList();

        assertThat(items).hasSize(3);
        ConversationItem.AgentCall call = (ConversationItem.AgentCall) items.get(0);
        assertThat(call.agentName()).isEqualTo("researcher");
        assertThat(call.childResponseId()).isEqualTo("sub-session");
        ConversationItem.FunctionCallOutput output = (ConversationItem.FunctionCallOutput) items.get(1);
        assertThat(output.callId()).isEqualTo(call.callId());
        assertThat(output.output()).isEqualTo("X found");
    }

    /** The call that hands the task over is the agent call: it does not show a second time as a function. */
    @Test
    void theCallThatHandsATaskToAnAgentIsNotAFunctionCall() {
        assembler.accept(called("c1", "run_agent", Map.of("name", "researcher", "message", "find X")));
        assembler.subAgentStarted("turn-2", "researcher", "sub-session");
        assembler.subAgentEnded("turn-2", "X found");
        assembler.accept(result("c1", "run_agent", "X found"));
        assembler.accept(done(Usage.ZERO));

        assertThat(assembler.snapshot().output()).extracting(entry -> entry.item().getClass().getSimpleName())
                .containsExactly("AgentCall", "FunctionCallOutput");
    }

    @Test
    void anAnswerThatWasNotStreamedIsHeardWhenItIsStored() {
        assembler.accept(new TurnEvent.MessageAppended(TURN, TurnMessage.assistant("whole answer")));
        assembler.accept(done(Usage.ZERO));

        assertThat(assembler.snapshot().outputText()).isEqualTo("whole answer");
    }

    @Test
    void aTurnThatRanOutOfRoundsEndsTheResponseIncomplete() {
        assembler.accept(new TurnEvent.TurnEnded(TURN,
                TurnOutcome.incomplete(TurnOutcome.IncompleteReason.MAX_ROUNDS, new Usage(5, 5), 3)));

        Response r = assembler.snapshot();
        assertThat(r.status()).isEqualTo(ResponseStatus.INCOMPLETE);
        assertThat(r.incompleteReason()).isEqualTo(IncompleteReason.MAX_ROUNDS);
    }

    /** A turn parked on its tools goes on by itself: the response stays open. */
    @Test
    void aTurnWaitingForItsToolsDoesNotEndTheResponse() {
        assembler.accept(new TurnEvent.TurnEnded(TURN,
                TurnOutcome.waitingForTools(java.util.Set.of("c1"), new Usage(5, 5), 1)));

        assertThat(assembler.snapshot().status()).isEqualTo(ResponseStatus.IN_PROGRESS);
    }

    @Test
    void reviewerRevisionReplacesStreamedText() {
        assembler.accept(new TurnEvent.TextDelta(TURN, "draft answer"));
        assembler.accept(new TurnEvent.MessageAppended(TURN, TurnMessage.assistant("reviewed answer")));
        assembler.accept(done(Usage.ZERO));

        assertThat(assembler.snapshot().outputText()).isEqualTo("reviewed answer");
    }

    @Test
    void replayDeliversEverythingLiveDeliversRest() {
        List<ResponseEvent> live = new ArrayList<>();
        assembler.accept(new TurnEvent.TextDelta(TURN, "Hi"));

        assembler.subscribe(0, live::add);              // replay: Created, InProgress, ItemAdded, Delta
        assembler.accept(done(Usage.ZERO));       // live: ItemDone, Completed

        assertThat(live).hasSize(6);
        assertThat(live.get(0)).isInstanceOf(ResponseEvent.Created.class);
        assertThat(live.get(3)).isInstanceOf(ResponseEvent.OutputTextDelta.class);
        assertThat(live.get(5)).isInstanceOf(ResponseEvent.Completed.class);
        // seq is strictly increasing across replay and live
        for (int i = 1; i < live.size(); i++) {
            assertThat(live.get(i).seq()).isGreaterThan(live.get(i - 1).seq());
        }
    }

    @Test
    void tokenCountsFromTheTurnReachTheResponse() {
        assembler.accept(new TurnEvent.TextDelta(TURN, "hi"));
        assembler.accept(done(new Usage(1200, 340)));

        Response r = assembler.snapshot();
        assertThat(r.usage().inputTokens()).isEqualTo(1200);
        assertThat(r.usage().outputTokens()).isEqualTo(340);
        assertThat(r.usage().totalTokens()).isEqualTo(1540);
    }

    /** A turn that was cancelled has still been billed: its outcome says what for. */
    @Test
    void tokenCountsSurviveACancelledTurn() {
        assembler.accept(new TurnEvent.TurnEnded(TURN, TurnOutcome.cancelled(new Usage(500, 40), 1)));

        Response r = assembler.snapshot();
        assertThat(r.status()).isEqualTo(ResponseStatus.CANCELLED);
        assertThat(r.usage().totalTokens()).isEqualTo(540);
    }

    /**
     * The backlog must be written before any live event, or a reader sees a
     * later delta ahead of an earlier one — and a terminal event that wins
     * the race closes the stream on a backlog never written.
     */
    @Test
    void liveEventsNeverOvertakeTheReplayedBacklog() throws Exception {
        for (int run = 0; run < 50; run++) {
            ResponseAssembler a = new ResponseAssembler("resp_" + run, "conv", "sess", "agent");
            for (int i = 0; i < 200; i++) {
                a.accept(new TurnEvent.TextDelta(TURN, "t" + i));
            }

            List<Long> seen = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < 50; i++) {
                    a.accept(new TurnEvent.TextDelta(TURN, "late" + i));
                }
            });
            producer.start();
            go.countDown();
            a.subscribe(0, e -> {
                synchronized (seen) {
                    seen.add(e.seq());
                }
            });
            producer.join();

            assertThat(seen).isSorted();
            assertThat(seen).doesNotHaveDuplicates();
        }
    }

    @Test
    void turnFailureAfterPartialText() {
        assembler.accept(new TurnEvent.TextDelta(TURN, "partial"));
        assembler.fail("LLM unavailable");

        Response r = assembler.snapshot();
        assertThat(r.status()).isEqualTo(ResponseStatus.FAILED);
        assertThat(r.error().message()).isEqualTo("LLM unavailable");
        assertThat(r.outputText()).isEqualTo("partial");   // flushed, not lost
    }
}
