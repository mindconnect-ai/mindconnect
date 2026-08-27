package ai.mindconnect.agent.service.round;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop against a scripted model and a hand-steered executor — no queue,
 * no threads: every wait surfaces as a {@link TurnOutcome} instead of
 * blocking, which is exactly what the task worker later builds on.
 */
class AgentLoopTest {

    private final UUID conversationId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    // ── fakes ───────────────────────────────────────────────────────────────

    /** In-memory MessageLog seeded like the API layer would: user question first. */
    private static final class InMemoryLog implements MessageLog {
        private final List<Message> messages = new ArrayList<>();
        private int seq;

        Message seed(UUID conversationId, ParticipantType sender, MessageType type,
                     String content, Map<String, Object> metadata) {
            Message m = Message.of(conversationId, UUID.randomUUID(), sender, type, content, ++seq)
                    .withMetadata(metadata);
            messages.add(m);
            return m;
        }

        @Override public List<Message> load(UUID conversationId) {
            return List.copyOf(messages);
        }

        @Override public Message append(UUID conversationId, TurnMessage turnMessage) {
            Message m = Message.of(conversationId, UUID.randomUUID(), turnMessage.senderType(),
                            turnMessage.type(), turnMessage.content(), ++seq)
                    .withMetadata(turnMessage.metadata());
            messages.add(m);
            return m;
        }

        List<MessageType> types() {
            return messages.stream().map(Message::type).toList();
        }
    }

    /** Answers from a script, one entry per model call. */
    private static final class ScriptedLlm implements LlmProvider {
        private final Deque<LlmAnswer> script = new ArrayDeque<>();
        int calls;

        ScriptedLlm answer(TurnMessage... messages) {
            script.add(LlmAnswer.of(List.of(messages), new Usage(10, 5)));
            return this;
        }

        @Override public LlmAnswer ask(String requestId, UUID sessionId, List<Message> history,
                                       List<ToolDefinition> toolDefinitions, Cancellation cancellation) {
            calls++;
            if (script.isEmpty()) throw new AssertionError("Model asked more often than scripted");
            return script.pop();
        }
    }

    /** Executor whose results the test sets by hand — "the tool finished now". */
    private static final class HandSteeredExecutorAgentRound implements AgentRoundToolExecutor {
        private final Map<String, ToolResult> results = new HashMap<>();
        private final List<String> executed = new ArrayList<>();

        void finishes(String callId, String output) {
            results.put(callId, ToolResult.ok(output));
        }

        void stillRunning(String callId) {
            results.put(callId, new ToolResult.Running());
        }

        @Override public void execute(String requestId, UUID sessionId, ToolCalls.Call call) {
            executed.add(call.callId());
            results.putIfAbsent(call.callId(), new ToolResult.Running());
        }

        @Override public ToolResult result(UUID sessionId, String callId) {
            return results.getOrDefault(callId, new ToolResult.Lost("unknown callId"));
        }
    }

    private static TurnMessage modelToolCall(String callId, String name) {
        return TurnMessage.toolCalls(
                "{\"toolCalls\":[{\"id\":\"" + callId + "\",\"name\":\"" + name + "\",\"arguments\":{}}]}",
                List.of(callId));
    }

    private record Fixture(InMemoryLog log, ScriptedLlm llm, HandSteeredExecutorAgentRound executor,
                           AgentLoop loop, List<Message> published) {

        static Fixture create(UUID conversationId) {
            InMemoryLog log = new InMemoryLog();
            log.seed(conversationId, ParticipantType.USER, MessageType.CHAT, "do it", Map.of());
            ScriptedLlm llm = new ScriptedLlm();
            HandSteeredExecutorAgentRound executor = new HandSteeredExecutorAgentRound();
            List<Message> published = new ArrayList<>();
            AgentLoop loop = new AgentLoop(
                    new AgentRound(llm, session -> List.of(), executor), log, 10, published::add);
            return new Fixture(log, llm, executor, loop, published);
        }

        TurnOutcome run(UUID conversationId, UUID sessionId) {
            return loop.run("req1", conversationId, sessionId, new Cancellation(), 0);
        }
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    void aPlainAnswerCompletesInOneRound() {
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(TurnMessage.assistant("hello"));

        TurnOutcome outcome = f.run(conversationId, sessionId);

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.text()).isEqualTo("hello");
        assertThat(outcome.usage().totalTokens()).isEqualTo(15);
        assertThat(f.log.types()).containsExactly(MessageType.CHAT, MessageType.CHAT);
    }

    @Test
    void aToolCallRunsDispatchCollectAnswer() {
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(modelToolCall("c1", "search"))
             .answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found it");

        TurnOutcome outcome = f.run(conversationId, sessionId);

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.executor.executed).containsExactly("c1");
        // user, TOOL_CALL, DISPATCHED, RESULT, final answer — in this order
        assertThat(f.log.types()).containsExactly(MessageType.CHAT, MessageType.TOOL_CALL,
                MessageType.TOOL_DISPATCHED, MessageType.TOOL_RESULT, MessageType.CHAT);
        // save first, then publish: everything appended was also announced
        assertThat(f.published).hasSize(4);
    }

    @Test
    void aRunningToolSurfacesAsWaitingForToolsInsteadOfBlocking() {
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");

        TurnOutcome outcome = f.run(conversationId, sessionId);

        assertThat(outcome.waitsForTools()).isTrue();
        assertThat(outcome.waitingFor()).containsExactly("c1");
    }

    @Test
    void aResumedTurnCollectsTheResultWithoutReaskingTheModel() {
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(modelToolCall("c1", "slow"));
        f.executor.stillRunning("c1");
        assertThat(f.run(conversationId, sessionId).waitsForTools()).isTrue();

        // ...the tool finishes while the turn holds no thread...
        f.executor.finishes("c1", "late result");
        f.llm.answer(TurnMessage.assistant("done"));

        TurnOutcome outcome = f.loop.run("req1", conversationId, sessionId, new Cancellation(), 1);

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        assertThat(f.executor.executed).containsExactly("c1");     // dispatched ONCE
        assertThat(f.llm.calls).isEqualTo(2);                      // no re-ask for the tool round
    }

    @Test
    void theRoundBrakeCountsAcrossResumes() {
        Fixture f = Fixture.create(conversationId);
        AgentLoop tightLoop = new AgentLoop(
                new AgentRound(f.llm, s -> List.of(), f.executor), f.log, 2, m -> { });
        f.llm.answer(modelToolCall("c1", "a")).answer(modelToolCall("c2", "b"));
        f.executor.finishes("c1", "ok");
        f.executor.finishes("c2", "ok");

        // roundsSoFar = 1 (an earlier attempt already turned once): the cap of
        // 2 is hit after ONE more model round — the history knows the calls,
        // only the count must come from outside.
        TurnOutcome outcome = tightLoop.run("req1", conversationId, sessionId, new Cancellation(), 1);

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.INCOMPLETE);
        assertThat(outcome.incompleteReason()).isEqualTo(TurnOutcome.IncompleteReason.MAX_ROUNDS);
        assertThat(f.llm.calls).isEqualTo(1);
    }

    @Test
    void cancellationWinsBetweenRounds() {
        Fixture f = Fixture.create(conversationId);
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();

        TurnOutcome outcome = f.loop.run("req1", conversationId, sessionId, cancellation, 0);

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.CANCELLED);
        assertThat(f.llm.calls).isZero();
    }

    @Test
    void theRealReviewerAdvisorRewritesTheAnswerThroughTheChain() {
        // Same seam as above, but with the PRODUCTION advisor: definition with
        // one reviewer, a scripted task runner as the reviewer agent — the
        // persisted answer is the chain's output, never the model's draft.
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(TurnMessage.assistant("model draft"));

        ai.mindconnect.agent.domain.AgentDefinition base =
                ai.mindconnect.agent.domain.AgentDefinition.create(
                        new ai.mindconnect.common.Namespace("test"), "a", "d", "p", null, "llm");
        ai.mindconnect.agent.domain.AgentDefinition def = base.withBasicFields(
                base.namespace(), base.name(), base.description(), base.systemPrompt(),
                base.welcomeMessage(), base.llmConfigName(), base.maxIterations(),
                List.of("tone-reviewer"));
        ai.mindconnect.agent.domain.AgentSession session =
                ai.mindconnect.agent.domain.AgentSession.startSubAgent(def.id(),
                        def.namespace(), "u", conversationId, null, null, null);
        ai.mindconnect.agent.port.in.AgentTaskRunner reviewerAgent =
                new ai.mindconnect.agent.port.in.AgentTaskRunner() {
                    @Override public String run(String task, String userMessage) {
                        return run(task, userMessage, Map.of());
                    }
                    @Override public String run(String task, String userMessage,
                                                Map<String, Object> variables) {
                        return "polite: " + variables.get("agent_response");
                    }
                };
        ai.mindconnect.message.port.in.ConversationManager noHistory =
                (ai.mindconnect.message.port.in.ConversationManager) java.lang.reflect.Proxy
                        .newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{ai.mindconnect.message.port.in.ConversationManager.class},
                                (proxy, method, args) -> switch (method.getName()) {
                                    case "loadHistory" -> List.<Message>of();
                                    default -> throw new UnsupportedOperationException(method.getName());
                                });
        // The advisor judges the answer AGAINST the user's question — without
        // one it deliberately skips reviewing, so the test hands it the
        // seeded question message.
        Message question = f.log.messages.get(0);
        var advisor = new ai.mindconnect.agent.service.task.ReviewerAdvisor(
                reviewerAgent, noHistory, def, session, question, event -> { });
        AgentLoop loop = new AgentLoop(new AgentRound(f.llm, s -> List.of(), f.executor),
                f.log, 10, m -> { }, List.of(advisor));

        TurnOutcome outcome = loop.run("req1", conversationId, sessionId, new Cancellation(), 0);

        assertThat(outcome.text()).isEqualTo("polite: model draft");
        Message persisted = f.log.messages.get(f.log.messages.size() - 1);
        assertThat(persisted.content()).isEqualTo("polite: model draft");
    }

    @Test
    void anAdvisorRewritesTheAnswerBeforeItIsPersisted() {
        // The reviewer chain as an advisor: the conversation must only ever
        // hold the reviewed text — and the outcome reports the same.
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(TurnMessage.assistant("draft"));
        AgentRoundAdvisor reviewer = new AgentRoundAdvisor() {
            @Override public RoundOutcome aroundRound(RoundContext context, Execution execution) {
                RoundOutcome out = execution.proceed();
                if (out.outcome() != RoundOutcome.Outcome.ANSWERED) return out;
                List<TurnMessage> reviewed = out.added().stream()
                        .map(m -> m.type() == MessageType.CHAT
                                ? new TurnMessage(m.type(), m.senderType(), "reviewed", m.metadata())
                                : m)
                        .toList();
                return new RoundOutcome(reviewed, out.outcome(), out.usage(), out.waitingFor());
            }
        };
        AgentLoop loop = new AgentLoop(new AgentRound(f.llm, s -> List.of(), f.executor),
                f.log, 10, m -> { }, List.of(reviewer));

        TurnOutcome outcome = loop.run("req1", conversationId, sessionId, new Cancellation(), 0);

        assertThat(outcome.text()).isEqualTo("reviewed");
        Message persisted = f.log.messages.get(f.log.messages.size() - 1);
        assertThat(persisted.content()).isEqualTo("reviewed");     // never "draft"
    }

    @Test
    void anAfterRoundAdvisorSeesEachRoundsPersistedMessages() {
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(modelToolCall("c1", "search")).answer(TurnMessage.assistant("done"));
        f.executor.finishes("c1", "found");
        List<List<MessageType>> rounds = new ArrayList<>();
        AgentRoundAdvisor observer = new AgentRoundAdvisor() {
            @Override public void afterRoundPersisted(RoundContext context, List<Message> persisted) {
                rounds.add(persisted.stream().map(Message::type).toList());
            }
        };
        AgentLoop loop = new AgentLoop(new AgentRound(f.llm, s -> List.of(), f.executor),
                f.log, 10, m -> { }, List.of(observer));

        loop.run("req1", conversationId, sessionId, new Cancellation(), 0);

        // one call per round, each with exactly that round's durable increment
        assertThat(rounds).containsExactly(
                List.of(MessageType.TOOL_CALL),
                List.of(MessageType.TOOL_DISPATCHED),
                List.of(MessageType.TOOL_RESULT),
                List.of(MessageType.CHAT));
    }

    @Test
    void aThrowingAfterRoundAdvisorDoesNotCostTheTurn() {
        Fixture f = Fixture.create(conversationId);
        f.llm.answer(TurnMessage.assistant("hello"));
        AgentRoundAdvisor broken = new AgentRoundAdvisor() {
            @Override public void afterRoundPersisted(RoundContext context, List<Message> persisted) {
                throw new IllegalStateException("boom");
            }
        };
        AgentLoop loop = new AgentLoop(new AgentRound(f.llm, s -> List.of(), f.executor),
                f.log, 10, m -> { }, List.of(broken));

        assertThat(loop.run("req1", conversationId, sessionId, new Cancellation(), 0).status())
                .isEqualTo(TurnOutcome.Status.COMPLETED);
    }

    @Test
    void aLostCallFailsInsteadOfWaitingForever() {
        // Executor restarted, callId gone: Lost ≠ Running — the round must
        // close the call with a readable error, not spin on it.
        Fixture f = Fixture.create(conversationId);
        f.log.seed(conversationId, ParticipantType.AGENT, MessageType.TOOL_CALL,
                "{\"toolCalls\":[{\"id\":\"c1\",\"name\":\"search\",\"arguments\":{}}]}",
                Map.of("callIds", List.of("c1")));
        f.log.seed(conversationId, ParticipantType.AGENT, MessageType.TOOL_DISPATCHED, "",
                Map.of("callId", "c1"));
        f.llm.answer(TurnMessage.assistant("sorry, that broke"));

        TurnOutcome outcome = f.run(conversationId, sessionId);

        assertThat(outcome.status()).isEqualTo(TurnOutcome.Status.COMPLETED);
        Message result = f.log.messages.stream()
                .filter(m -> m.type() == MessageType.TOOL_RESULT).findFirst().orElseThrow();
        assertThat(result.metadata()).containsEntry("failed", true);
        assertThat(result.content()).contains("interrupted before completion");
    }
}
