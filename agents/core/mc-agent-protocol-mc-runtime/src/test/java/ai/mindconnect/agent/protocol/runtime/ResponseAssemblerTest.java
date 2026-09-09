package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole runtime→protocol translation, offline — no runtime, no LLM. */
class ResponseAssemblerTest {

    private final ResponseAssembler assembler =
            new ResponseAssembler("resp_1", "conv_1", "sess_1", "test-agent");

    @Test
    void fullTurn_tokensToolsAndAnswer() {
        assembler.accept(new StreamEvent.ToolCallStarted("web_search", Map.of("q", "lisbon")));
        assembler.accept(new StreamEvent.ToolCallResult("web_search", "3 results", 42));
        assembler.accept(new StreamEvent.Token("It is "));
        assembler.accept(new StreamEvent.Token("sunny."));
        assembler.accept(new StreamEvent.Done());

        Response r = assembler.snapshot();

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).isEqualTo("It is sunny.");
        List<ConversationItem> items = r.output().stream().map(ConversationItemRecord::item).toList();
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).isInstanceOf(ConversationItem.FunctionCall.class);
        assertThat(items.get(1)).isInstanceOf(ConversationItem.FunctionCallOutput.class);
        // call and result are paired by id even though StreamEvents carry none:
        assertThat(((ConversationItem.FunctionCall) items.get(0)).callId())
                .isEqualTo(((ConversationItem.FunctionCallOutput) items.get(1)).callId());
        assertThat(r.openFunctionCalls()).isEmpty();
    }

    @Test
    void subAgentBecomesAgentCallPair() {
        UUID taskId = UUID.randomUUID();
        UUID subSession = UUID.randomUUID();
        assembler.accept(new StreamEvent.SubAgentStarted(taskId, "researcher", 1, subSession, "find X"));
        assembler.accept(new StreamEvent.SubAgentDone(taskId, "researcher", subSession, "X found"));
        assembler.accept(new StreamEvent.Token("done"));
        assembler.accept(new StreamEvent.Done());

        List<ConversationItem> items = assembler.snapshot().output().stream().map(ConversationItemRecord::item).toList();

        ConversationItem.AgentCall call = (ConversationItem.AgentCall) items.get(0);
        assertThat(call.agentName()).isEqualTo("researcher");
        assertThat(call.childResponseId()).isEqualTo(subSession.toString());
        ConversationItem.FunctionCallOutput output = (ConversationItem.FunctionCallOutput) items.get(1);
        assertThat(output.callId()).isEqualTo(call.callId());
        assertThat(output.output()).isEqualTo("X found");
    }

    @Test
    void reviewerRevisionReplacesStreamedText() {
        assembler.accept(new StreamEvent.Token("draft answer"));
        assembler.accept(new StreamEvent.ResponseRevised("reviewed answer", "tone", false));
        assembler.accept(new StreamEvent.Done());

        assertThat(assembler.snapshot().outputText()).isEqualTo("reviewed answer");
    }

    @Test
    void replayDeliversEverythingLiveDeliversRest() {
        List<ResponseEvent> live = new ArrayList<>();
        assembler.accept(new StreamEvent.Token("Hi"));

        assembler.subscribe(0, live::add);              // replay: Created, InProgress, ItemAdded, Delta
        assembler.accept(new StreamEvent.Done());       // live: ItemDone, Completed

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
        assembler.accept(new StreamEvent.Token("hi"));
        assembler.accept(new StreamEvent.TurnUsage(1200, 340));
        assembler.accept(new StreamEvent.Done());

        Response r = assembler.snapshot();
        assertThat(r.usage().inputTokens()).isEqualTo(1200);
        assertThat(r.usage().outputTokens()).isEqualTo(340);
        assertThat(r.usage().totalTokens()).isEqualTo(1540);
    }

    /**
     * A turn that dies has still been billed. The counts arrive with
     * TurnUsage before the loop hands back, so they survive a failure and a
     * cancellation — the two cases a cost investigation actually looks at.
     */
    @Test
    void tokenCountsSurviveAFailedTurn() {
        assembler.accept(new StreamEvent.TurnUsage(900, 120));
        assembler.accept(new StreamEvent.Token("partial"));
        assembler.fail("LLM unavailable");

        Response r = assembler.snapshot();
        assertThat(r.status()).isEqualTo(ResponseStatus.FAILED);
        assertThat(r.usage().totalTokens()).isEqualTo(1020);
    }

    @Test
    void tokenCountsSurviveACancelledTurn() {
        assembler.accept(new StreamEvent.TurnUsage(500, 40));
        assembler.cancelled();

        assertThat(assembler.snapshot().usage().totalTokens()).isEqualTo(540);
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
                a.accept(new StreamEvent.Token("t" + i));
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
                    a.accept(new StreamEvent.Token("late" + i));
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
        assembler.accept(new StreamEvent.Token("partial"));
        assembler.fail("LLM unavailable");

        Response r = assembler.snapshot();
        assertThat(r.status()).isEqualTo(ResponseStatus.FAILED);
        assertThat(r.error().message()).isEqualTo("LLM unavailable");
        assertThat(r.outputText()).isEqualTo("partial");   // flushed, not lost
    }
}
