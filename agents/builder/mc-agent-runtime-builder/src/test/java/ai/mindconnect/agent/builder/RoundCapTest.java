package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.lmstudio.TestTools;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.domain.TurnStatus;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The round cap of a turn is the agent's {@code maxIterations}, not a constant —
 * and when it hits, the conversation says so before the forced answer.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RoundCapTest {

    private static final UserId USER = UserId.of("tester");

    private AgentRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.close();
    }

    @Test
    void theAgentsMaxIterationsIsTheRoundCap() {
        runtime = runtimeWith(agent("two", 2));
        SessionId session = runtime.openSession("two", USER).id();

        TurnResult result = runtime.send(session, "go", e -> { });

        assertThat(result.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(TestTools.INVOCATIONS).as("exactly the agent's rounds ran").hasSize(2);
        List<String> agentTexts = agentTexts(session);
        assertThat(agentTexts).anySatisfy(t -> assertThat(t)
                .contains("Round cap reached")
                .contains("all 2 tool-call rounds"));
        assertThat(agentTexts.get(agentTexts.size() - 1))
                .as("the forced answer without tools comes after the note").isEqualTo("stopped");
        assertThat(toolResults(session)).as("two executed, the refused third closed with an error")
                .hasSize(3)
                .last().satisfies(m -> assertThat(m.content()).contains("round cap of 2"));
    }

    @Test
    void aDefinitionWithoutMaxIterationsFallsBackToTheDefault() {
        runtime = runtimeWith(agent("zero", 0));
        SessionId session = runtime.openSession("zero", USER).id();

        runtime.send(session, "go", e -> { });

        // AgentTurnWorker.DEFAULT_MAX_ROUNDS — the cap every agent had before it was configurable.
        assertThat(TestTools.INVOCATIONS).hasSize(10);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static AgentRuntime runtimeWith(AgentDefinition def) {
        TestTools.reset();
        return AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("scripted", "scripted-model", "http://localhost:1"))
                .install(new RuntimeFeature() {
                    @Override public String name() { return "endless-llm"; }
                    @Override public void configure(FeatureContext ctx) {
                        ctx.decorate(LlmChat.class, original -> new EndlessLlm());
                    }
                })
                .agentDefinition(def)
                .build();
    }

    private List<String> agentTexts(SessionId session) {
        var conversation = runtime.sessionService().findSession(session).conversationId();
        return runtime.conversationManager().loadCompleteHistory(conversation).messages().stream()
                .filter(m -> m.type() == MessageType.CHAT
                        && m.senderType() == ai.mindconnect.message.domain.ParticipantType.AGENT)
                .map(Message::content)
                .toList();
    }

    private List<Message> toolResults(SessionId session) {
        var conversation = runtime.sessionService().findSession(session).conversationId();
        return runtime.conversationManager().loadCompleteHistory(conversation).messages().stream()
                .filter(m -> m.type() == MessageType.TOOL_RESULT).toList();
    }

    private static AgentDefinition agent(String name, int maxIterations) {
        AgentTool echo = new AgentTool(AgentToolId.random(), "it_echo", null, Map.of(), true, false, false, null);
        AgentDefinition base = AgentDefinition.create(name, "endless test agent", "ROLE:" + name, null, "scripted");
        return new AgentDefinition(base.id(), base.name(), base.description(), base.group(), base.icon(),
                base.systemPrompt(), base.welcomeMessage(), base.llmConfigName(), maxIterations,
                base.memoryConfig(), base.status(), List.of(echo), base.responseReviewers(),
                List.of(), base.callableByAgents(), base.createdAt(), base.updatedAt());
    }

    /** A model that never stops calling tools — until it is asked without any, then it answers "stopped". */
    static class EndlessLlm implements LlmChat {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void chatStreaming(LlmRequest request, Consumer<LlmStreamChunk> handler,
                                  Cancellation cancellation, LlmCallListener listener) {
            if (request.tools() == null || request.tools().isEmpty()) {
                handler.accept(new LlmStreamChunk.TextDelta("stopped"));
                handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
                return;
            }
            handler.accept(new LlmStreamChunk.ToolCallDelta(0, "call_" + calls.incrementAndGet(),
                    "it_echo", "{\"text\":\"again\"}"));
            handler.accept(new LlmStreamChunk.Done(FinishReason.TOOL_CALLS, 1, 1));
        }
    }
}
