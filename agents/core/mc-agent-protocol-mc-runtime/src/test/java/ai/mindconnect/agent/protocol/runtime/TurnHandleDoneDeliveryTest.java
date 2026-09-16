package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.in.LlmChat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A turn's events reach its listener on the session channel's own drain thread,
 * so a turn task can end before its {@code Done} has been handed over. The
 * handle's outcome must not complete in that gap: whoever reads the result on
 * completion — the protocol backend taking its response snapshot — would see a
 * turn still in progress. A slow listener makes the gap wide enough to hit.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TurnHandleDoneDeliveryTest {

    private AgentRuntime runtime;
    private AgentDefinition agent;

    @BeforeEach
    void setUp() {
        agent = AgentDefinition.create("plain", "Answers.", "You answer.", null, "scripted");
        runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("scripted", "scripted-model", "http://127.0.0.1:9"))
                .install(new AnsweringLlmFeature())
                .agentDefinition(agent)
                .build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void theOutcomeCompletesOnlyAfterTheListenerGotDone() {
        var session = runtime.sessionService().openChat(agent.id(), UserId.of("alice"));
        List<StreamEvent> seen = new CopyOnWriteArrayList<>();

        var handle = runtime.chatService().sendChat(session.id(), "hi", event -> {
            if (event instanceof StreamEvent.Done) {
                busy(300);
            }
            seen.add(event);
        });
        handle.outcome().join();

        assertThat(seen).isNotEmpty();
        assertThat(seen.get(seen.size() - 1)).isInstanceOf(StreamEvent.Done.class);
    }

    /**
     * Slow without sleeping: closing a subscription interrupts its drain thread
     * to hand over what is queued, and a sleep would end right there.
     */
    private static void busy(long millis) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    static class AnsweringLlmFeature implements RuntimeFeature {
        @Override public String name() { return "answering-llm"; }
        @Override public void configure(FeatureContext ctx) {
            ctx.decorate(LlmChat.class, original -> new AnsweringLlm());
        }
    }

    /** Answers "hello" to everything, without tools. */
    static class AnsweringLlm implements LlmChat {
        @Override
        public void chatStreaming(LlmRequest request, Consumer<LlmStreamChunk> handler,
                                  Cancellation cancellation, LlmCallListener listener) {
            handler.accept(new LlmStreamChunk.TextDelta("hello"));
            handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
        }
    }
}
