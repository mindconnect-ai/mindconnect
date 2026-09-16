package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.lmstudio.TestTools;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.TurnStatus;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.MessageRole;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A turn that waits at the approval gate ends INCOMPLETE for its caller, and the answer
 * continues the same turn — against a scripted model, so every case runs without a server.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class IncompleteTurnTest {

    private static final UserId USER = UserId.of("tester");

    private AgentRuntime runtime;
    private final List<StreamEvent> events = new CopyOnWriteArrayList<>();
    private final Consumer<StreamEvent> recorder = events::add;

    @BeforeEach
    void setUp() {
        TestTools.reset();
        runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("scripted", "scripted-model", "http://localhost:1"))
                .install(new ScriptedLlmFeature())
                .agentDefinition(agent("single", List.of(echo(true)), List.of()))
                .agentDefinition(agent("parallel", List.of(echo(true)), List.of()))
                .agentDefinition(agent("parent", List.of(), List.of("child")))
                .agentDefinition(agent("child", List.of(echo(true)), List.of()))
                .build();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void sendStopsAtTheGateAndApproveContinuesTheSameTurn() {
        SessionId session = open("single");

        TurnResult first = runtime.send(session, "go", recorder);

        assertThat(first.status()).isEqualTo(TurnStatus.INCOMPLETE);
        assertThat(first.text()).isNull();
        assertThat(first.pendingApprovals()).extracting(ToolApproval::toolName).containsExactly("it_echo");
        assertThat(TestTools.INVOCATIONS).as("the gated tool did not run").isEmpty();

        events.clear();
        TurnResult done = runtime.approve(session, first.pendingApprovals().get(0).callId(),
                ApprovalScope.ONCE, recorder);

        assertThat(done.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(done.turnId()).isEqualTo(first.turnId());
        assertThat(done.text()).isEqualTo("done");
        assertThat(TestTools.INVOCATIONS).containsExactly("it_echo:single");
        assertThat(events).as("the continuation carries what happened after the gate")
                .anyMatch(StreamEvent.ToolCallResult.class::isInstance)
                .anyMatch(StreamEvent.Done.class::isInstance);
        assertThat(history(session)).allMatch(m -> m.runOrZero() == 0, "no resume run");
    }

    @Test
    void denyClosesTheCallAndTheTurnCompletes() {
        SessionId session = open("single");
        TurnResult first = runtime.send(session, "go", recorder);

        TurnResult done = runtime.deny(session, first.pendingApprovals().get(0).callId(), recorder);

        assertThat(done.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(TestTools.INVOCATIONS).isEmpty();
        assertThat(toolResults(session)).singleElement()
                .satisfies(m -> assertThat(m.content()).contains("did not approve"));
    }

    @Test
    void parallelQuestionsAreAnsweredOneAfterTheOther() {
        SessionId session = open("parallel");

        TurnResult result = runtime.send(session, "go", recorder);
        int answers = 0;
        while (result.isIncomplete()) {
            assertThat(answers).as("never more questions than calls").isLessThan(2);
            result = runtime.approve(session, result.pendingApprovals().get(0).callId(),
                    ApprovalScope.ONCE, recorder);
            answers++;
        }

        assertThat(answers).isEqualTo(2);
        assertThat(result.text()).isEqualTo("done");
        assertThat(TestTools.INVOCATIONS).containsExactlyInAnyOrder("it_echo:a", "it_echo:b");
        assertThat(events).as("each tool result heard once across all handles — none lost, none twice")
                .filteredOn(StreamEvent.ToolCallResult.class::isInstance).hasSize(2);
        assertThat(events).filteredOn(StreamEvent.Done.class::isInstance).hasSize(1);
    }

    @Test
    void aSubAgentsQuestionEndsTheRootTurnsHandle() {
        SessionId session = open("parent");

        TurnResult first = runtime.send(session, "go", recorder);

        assertThat(first.status()).isEqualTo(TurnStatus.INCOMPLETE);
        ToolApproval question = first.pendingApprovals().get(0);
        assertThat(question.originSessionId()).as("asked by the child").isNotEqualTo(session);

        TurnResult done = runtime.approve(session, question.callId(), ApprovalScope.ONCE, recorder);

        assertThat(done.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(TestTools.INVOCATIONS).containsExactly("it_echo:child");
    }

    @Test
    void aNewMessageSupersedesTheWaitingTurn() {
        SessionId session = open("single");
        ChatTurnHandle waiting = runtime.chatService().sendChat(session, "go", recorder);
        TurnResult first = waiting.outcome().join();
        String oldCall = first.pendingApprovals().get(0).callId();

        TurnResult second = runtime.send(session, "again", recorder);

        assertThat(second.status()).as("the new turn asks for itself").isEqualTo(TurnStatus.INCOMPLETE);
        assertThat(second.turnId()).isNotEqualTo(first.turnId());
        assertThat(toolResults(session)).filteredOn(m -> oldCall.equals(m.metadata().get("callId")))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.content()).contains("Not approved: superseded by a new message");
                    assertThat(m.metadata()).containsEntry("approval", "denied")
                            .containsEntry("reason", "superseded");
                });
        assertThatThrownBy(() -> waiting.result().join())
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(runtime.chatService().approve(session, oldCall, ApprovalScope.ONCE, recorder))
                .as("the old question is gone").isEmpty();
        assertThat(TestTools.INVOCATIONS).isEmpty();
    }

    @Test
    void submitChatStillHearsTheWholeTurn() {
        SessionId session = open("single");
        ChatTurnHandle handle = runtime.chatService().submitChat(session, "go", recorder);

        TurnResult first = handle.outcome().join();
        assertThat(first.isIncomplete()).isTrue();
        assertThat(handle.status()).isEqualTo(TurnStatus.INCOMPLETE);

        runtime.chatService().answerApproval(session, first.pendingApprovals().get(0).callId(),
                true, ApprovalScope.ONCE);

        assertThat(handle.result().join()).isEqualTo("done");
        assertThat(handle.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(events).as("the original handler hears the turn to its end")
                .anyMatch(StreamEvent.Done.class::isInstance);
    }

    @Test
    void anAnswerToNothingContinuesNothing() {
        SessionId session = open("single");
        runtime.send(session, "go", recorder);

        assertThat(runtime.chatService().approve(session, "no-such-call", ApprovalScope.ONCE, recorder))
                .isEmpty();
        assertThatThrownBy(() -> runtime.deny(session, "no-such-call", recorder))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private SessionId open(String agent) {
        AgentSession session = runtime.openSession(agent, USER);
        return session.id();
    }

    private List<Message> history(SessionId session) {
        var conversation = runtime.sessionService().findSession(session).conversationId();
        return runtime.conversationManager().loadCompleteHistory(conversation).messages();
    }

    private List<Message> toolResults(SessionId session) {
        return history(session).stream().filter(m -> m.type() == MessageType.TOOL_RESULT).toList();
    }

    private static AgentTool echo(boolean needsApproval) {
        return new AgentTool(AgentToolId.random(), "it_echo", null, Map.of(), true, false, needsApproval, null);
    }

    private static AgentDefinition agent(String name, List<AgentTool> tools, List<String> callable) {
        AgentDefinition base = AgentDefinition.create(name, "scripted test agent", "ROLE:" + name, null, "scripted");
        return new AgentDefinition(base.id(), base.name(), base.description(), base.group(), base.icon(),
                base.systemPrompt(), base.welcomeMessage(), base.llmConfigName(), base.maxIterations(),
                base.memoryConfig(), base.status(), List.copyOf(tools), base.responseReviewers(),
                List.copyOf(callable), base.callableByAgents(), base.createdAt(), base.updatedAt());
    }

    /** Replaces the model: which calls it makes depends on the agent's role, then it answers "done". */
    static class ScriptedLlmFeature implements RuntimeFeature {
        @Override public String name() { return "scripted-llm"; }
        @Override public void configure(FeatureContext ctx) {
            ctx.decorate(LlmChat.class, original -> new ScriptedLlm());
        }
    }

    static class ScriptedLlm implements LlmChat {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void chatStreaming(LlmRequest request, Consumer<LlmStreamChunk> handler,
                                  Cancellation cancellation, LlmCallListener listener) {
            List<LlmMessage> messages = request.messages();
            LlmMessage last = messages.get(messages.size() - 1);
            if (last.role() == MessageRole.TOOL) {
                handler.accept(new LlmStreamChunk.TextDelta("done"));
                handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
                return;
            }
            String system = messages.stream().filter(m -> m.role() == MessageRole.SYSTEM)
                    .map(LlmMessage::content).filter(java.util.Objects::nonNull)
                    .findFirst().orElse("");
            if (system.contains("ROLE:parallel")) {
                call(handler, 0, "it_echo", "{\"text\":\"a\"}");
                call(handler, 1, "it_echo", "{\"text\":\"b\"}");
            } else if (system.contains("ROLE:parent")) {
                call(handler, 0, "run_agent", "{\"name\":\"child\",\"message\":\"go\"}");
            } else if (system.contains("ROLE:child")) {
                call(handler, 0, "it_echo", "{\"text\":\"child\"}");
            } else {
                call(handler, 0, "it_echo", "{\"text\":\"single\"}");
            }
            handler.accept(new LlmStreamChunk.Done(FinishReason.TOOL_CALLS, 1, 1));
        }

        private void call(Consumer<LlmStreamChunk> handler, int index, String name, String arguments) {
            handler.accept(new LlmStreamChunk.ToolCallDelta(index, "call_" + calls.incrementAndGet(),
                    name, arguments));
        }
    }
}
