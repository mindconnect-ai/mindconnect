package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.builder.lmstudio.TestTools;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryToolApprovalRepository;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.TurnStatus;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
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
                .agentDefinition(agent("sequential", List.of(echo(true)), List.of()))
                .build();
    }

    @AfterEach
    void tearDown() {
        ScriptedLlm.hold = null;
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
    void anAnswerContinuesFromTheLatestGate_evenWhenTheFirstWasAnsweredWithoutAHandle() throws Exception {
        SessionId session = open("sequential");
        ChatTurnHandle handle = runtime.chatService().submitChat(session, "go", recorder);
        String firstCall = handle.outcome().join().pendingApprovals().get(0).callId();

        // The first question answered as a plain answer: nobody takes a handle on what follows.
        runtime.chatService().answerApproval(session, firstCall, true, ApprovalScope.ONCE);
        awaitEvents(StreamEvent.ApprovalRequested.class, 2);
        ToolApproval second = runtime.toolApprovals().openForRoot(session).get(0);
        assertThat(second.callId()).isNotEqualTo(firstCall);

        List<StreamEvent> continued = new CopyOnWriteArrayList<>();
        TurnResult done = runtime.approve(session, second.callId(), ApprovalScope.ONCE, continued::add);

        assertThat(done.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(done.text()).isEqualTo("done");
        assertThat(TestTools.INVOCATIONS).containsExactly("it_echo:one", "it_echo:two");
        assertThat(continued).as("the continuation starts at the second gate, not the first")
                .filteredOn(StreamEvent.ToolCallResult.class::isInstance).hasSize(1);
        assertThat(continued).noneMatch(StreamEvent.ApprovalRequested.class::isInstance);
    }

    @Test
    void aQuestionAnsweredWhileItsEventTravelsEndsNoHandle() throws Exception {
        runtime.close();
        AnsweringApprovals approvals = new AnsweringApprovals(Thread.currentThread());
        runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("scripted", "scripted-model", "http://localhost:1"))
                .install(new ScriptedLlmFeature())
                .install(new RuntimeFeature() {
                    @Override public String name() { return "approval-storage"; }
                    @Override public void configure(FeatureContext ctx) {
                        ctx.instance(ToolApprovalRepository.class, approvals);
                    }
                })
                .agentDefinition(agent("held", List.of(echo(true)), List.of()))
                .build();
        SessionId session = open("held");
        approvals.answer = call -> runtime.chatService().answerApproval(session, call, true, ApprovalScope.ONCE);
        ScriptedLlm.hold = new java.util.concurrent.CountDownLatch(1);

        ChatTurnHandle handle;
        try {
            handle = runtime.chatService().sendChat(session, "go", recorder);
        } finally {
            // The model answers only once the handle listens, so the question reaches it as an event.
            ScriptedLlm.hold.countDown();
        }
        TurnResult result = handle.outcome().get(20, TimeUnit.SECONDS);

        assertThat(approvals.answered).as("the question was answered as the handle read it").isTrue();
        assertThat(result.status()).as("no INCOMPLETE without a question").isEqualTo(TurnStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("done");
        assertThat(TestTools.INVOCATIONS).containsExactly("it_echo:held");
    }

    /**
     * Memory, but the first read of the open questions off the caller's thread — the handle
     * hearing the gate's event — answers the question just before it reads: the answer lands
     * between the event and the handle's look at it.
     */
    static class AnsweringApprovals extends InMemoryToolApprovalRepository {
        private final Thread caller;
        private final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        volatile Consumer<String> answer;
        volatile boolean answered;

        AnsweringApprovals(Thread caller) {
            this.caller = caller;
        }

        @Override
        public boolean saveIfAbsent(ToolApproval approval) {
            boolean added = super.saveIfAbsent(approval);
            if (added) armed.set(true);
            return added;
        }

        @Override
        public List<ToolApproval> openForRoot(SessionId rootSessionId) {
            if (Thread.currentThread() != caller && armed.compareAndSet(true, false)) {
                for (ToolApproval open : super.openForRoot(rootSessionId)) answer.accept(open.callId());
                answered = true;
            }
            return super.openForRoot(rootSessionId);
        }
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

    @Test
    void aFeatureBringsItsOwnApprovalRepository() {
        runtime.close();
        RecordingApprovals mine = new RecordingApprovals();
        runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("scripted", "scripted-model", "http://localhost:1"))
                .install(new ScriptedLlmFeature())
                .install(new RuntimeFeature() {
                    @Override public String name() { return "approval-storage"; }
                    @Override public void configure(FeatureContext ctx) {
                        ctx.instance(ToolApprovalRepository.class, mine);
                    }
                })
                .agentDefinition(agent("single", List.of(echo(true)), List.of()))
                .build();
        SessionId session = open("single");

        TurnResult first = runtime.send(session, "go", recorder);
        TurnResult done = runtime.approve(session, first.pendingApprovals().get(0).callId(),
                ApprovalScope.ONCE, recorder);

        assertThat(runtime.toolApprovals()).isSameAs(mine);
        assertThat(mine.saved).as("the gate parked the call in the feature's repository").hasSize(1);
        assertThat(done.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(mine.openForRoot(session)).as("answered, so gone").isEmpty();
    }

    /** Memory, but it remembers what the gate saved — stands in for a repository of one's own. */
    static class RecordingApprovals extends InMemoryToolApprovalRepository {
        final List<ToolApproval> saved = new CopyOnWriteArrayList<>();

        @Override
        public boolean saveIfAbsent(ToolApproval approval) {
            boolean added = super.saveIfAbsent(approval);
            if (added) saved.add(approval);
            return added;
        }
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private SessionId open(String agent) {
        AgentSession session = runtime.openSession(agent, USER);
        return session.id();
    }

    /** Waits until the recorder heard {@code count} events of {@code type}, and the handler past them a moment more. */
    private void awaitEvents(Class<? extends StreamEvent> type, int count) throws InterruptedException {
        while (events.stream().filter(type::isInstance).count() < count) {
            Thread.sleep(10);
        }
        // The handle looks at an event after handing it to the recorder.
        Thread.sleep(200);
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
        /** When set, the {@code held} agent's first call waits for it. */
        static volatile java.util.concurrent.CountDownLatch hold;

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void chatStreaming(LlmRequest request, Consumer<LlmStreamChunk> handler,
                                  Cancellation cancellation, LlmCallListener listener) {
            List<LlmMessage> messages = request.messages();
            LlmMessage last = messages.get(messages.size() - 1);
            String system = messages.stream().filter(m -> m.role() == MessageRole.SYSTEM)
                    .map(LlmMessage::content).filter(java.util.Objects::nonNull)
                    .findFirst().orElse("");
            // The sequential agent asks twice in one turn: a second call after the first one's result.
            if (system.contains("ROLE:sequential")
                    && messages.stream().filter(m -> m.role() == MessageRole.TOOL).count() == 1) {
                call(handler, 0, "it_echo", "{\"text\":\"two\"}");
                handler.accept(new LlmStreamChunk.Done(FinishReason.TOOL_CALLS, 1, 1));
                return;
            }
            if (last.role() == MessageRole.TOOL) {
                handler.accept(new LlmStreamChunk.TextDelta("done"));
                handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
                return;
            }
            if (system.contains("ROLE:held") && hold != null) {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (system.contains("ROLE:sequential")) {
                call(handler, 0, "it_echo", "{\"text\":\"one\"}");
            } else if (system.contains("ROLE:held")) {
                call(handler, 0, "it_echo", "{\"text\":\"held\"}");
            } else if (system.contains("ROLE:parallel")) {
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
