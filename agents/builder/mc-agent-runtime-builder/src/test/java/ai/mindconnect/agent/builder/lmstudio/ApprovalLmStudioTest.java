package ai.mindconnect.agent.builder.lmstudio;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.approval.ApprovalScope;
import ai.mindconnect.agent.service.approval.ToolApproval;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static ai.mindconnect.agent.builder.lmstudio.LmStudioSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The approval GATE against the REAL local LM Studio — deny, allow once,
 * allow for the session, parallel calls, and cancel mid-tool. Skipped
 * entirely when LM Studio is down.
 *
 * <p>Gate model: the turn never ends while a human is asked — the tool task
 * parks, the open question is a {@code ToolApprovalStore} entry, the answer
 * travels as a task notification, and the turn continues on its ORIGINAL
 * stream. No APPROVAL messages, no resumes, no runs.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ApprovalLmStudioTest {

    @BeforeEach
    void requireLmStudio() {
        assumeLmStudio();
        TestTools.reset();
    }

    private record Chat(AgentRuntime runtime, AgentChatService service, AgentSession session,
                        UUID conversationId) implements AutoCloseable {
        @Override public void close() {
            runtime.close();
        }
    }

    private Chat openChat() {
        AgentRuntime runtime = runtime("approver", ROBOT_PROMPT, null, List.of(
                tool("it_echo", true),
                tool("it_slow", false)));
        AgentSession session = runtime.openSession("approver", "tester");
        return new Chat(runtime, runtime.chatService(), session, session.conversationId());
    }

    /**
     * Sends the message NON-blocking and waits for the gate to park the call:
     * the store holds the open question, the turn task stays alive
     * (suspended), no tool ran yet.
     */
    private record Pending(ChatTurnHandle handle, ToolApproval open) { }

    private Pending askUntilParked(Chat chat, String message) {
        ChatTurnHandle handle = chat.service.submitChat(chat.session.id(), message, event -> { });
        boolean parked = awaitTrue(
                () -> openApproval(chat.runtime, chat.session.id()) != null, Duration.ofSeconds(60));
        assumeTrue(parked, "model produced no gated tool call in time");
        ToolApproval open = openApproval(chat.runtime, chat.session.id());
        assertThat(open.toolName()).isEqualTo("it_echo");
        assertThat(TestTools.INVOCATIONS).as("the gated tool must not have run yet").isEmpty();
        assertThat(handle.result().isDone()).as("the turn is WAITING, not ended").isFalse();
        return new Pending(handle, open);
    }

    @Test
    void denyLeavesTheToolUnrunAndTheModelAnswers() {
        try (Chat chat = openChat()) {
            Pending pending = askUntilParked(chat, "Call the tool it_echo with text='geheim'.");

            boolean delivered = chat.service.answerApproval(
                    chat.session.id(), pending.open().callId(), false, ApprovalScope.ONCE);
            assertThat(delivered).isTrue();
            String answer = pending.handle().result().join();

            assertThat(TestTools.INVOCATIONS).as("denied tool must never execute").isEmpty();
            var results = ofType(history(chat.runtime, chat.conversationId), MessageType.TOOL_RESULT);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).content()).contains("did not approve");
            assertThat(answer).as("the model answers despite the denial").isNotBlank();
            assertThat(openApproval(chat.runtime, chat.session.id())).as("card gone").isNull();
        }
    }

    @Test
    void allowOnceRunsTheToolOnTheOriginalTurn() {
        try (Chat chat = openChat()) {
            Pending pending = askUntilParked(chat, "Call the tool it_echo with text='hallo'.");

            chat.service.answerApproval(
                    chat.session.id(), pending.open().callId(), true, ApprovalScope.ONCE);
            String answer = pending.handle().result().join();

            assertThat(TestTools.INVOCATIONS).contains("it_echo:hallo");
            assertThat(answer).isNotBlank();
            var history = history(chat.runtime, chat.conversationId);
            var turnIds = history.messages().stream().map(Message::turnId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            assertThat(turnIds).as("one logical turn").hasSize(1);
            int maxRun = history.messages().stream().mapToInt(Message::runOrZero).max().orElse(0);
            assertThat(maxRun).as("no resume execution — the gate never ends the turn").isZero();
        }
    }

    @Test
    void allowForSessionSilencesTheNextCall() {
        try (Chat chat = openChat()) {
            Pending pending = askUntilParked(chat, "Call the tool it_echo with text='erster'.");
            chat.service.answerApproval(
                    chat.session.id(), pending.open().callId(), true, ApprovalScope.SESSION);
            assertThat(pending.handle().result().join()).isNotBlank();
            assertThat(TestTools.INVOCATIONS).contains("it_echo:erster");

            String second = chat.runtime.chat(chat.session.id(),
                    "Call the tool it_echo with text='zweiter'.", event -> { });

            assertThat(second).as("session-approved tool runs without asking").isNotBlank();
            assertThat(TestTools.INVOCATIONS).contains("it_echo:zweiter");
            assertThat(openApproval(chat.runtime, chat.session.id()))
                    .as("no second card ever needed").isNull();
        }
    }

    @Test
    void parallelCallsTheGateParksOneWhileTheOtherRuns() {
        // The model must emit BOTH calls in ONE response — a model choice we
        // can only prompt for; some models always serialise (premise-skip).
        Chat chat = null;
        ChatTurnHandle handle = null;
        boolean premise = false;
        for (int attempt = 0; attempt < 3 && !premise; attempt++) {
            if (chat != null) chat.close();
            TestTools.reset();
            TestTools.slowMillis = 12_000;
            chat = openChat();
            final Chat c = chat;
            handle = chat.service.submitChat(chat.session.id(),
                    "Emit exactly TWO tool calls TOGETHER in your first response — "
                            + "it_slow with text='s' and it_echo with text='e' — in the SAME "
                            + "assistant message, in parallel. Never call them one after the other.",
                    event -> { });
            premise = awaitTrue(() -> openApproval(c.runtime, c.session.id()) != null
                            && slowStillRunning(c), Duration.ofSeconds(45));
        }
        try (Chat c = chat) {
            assumeTrue(premise, "model did not call both tools in one response — premise failed");

            ToolApproval open = openApproval(c.runtime, c.session.id());
            c.service.answerApproval(c.session.id(), open.callId(), true, ApprovalScope.ONCE);

            String answer = handle.result().join();
            assertThat(answer).as("the ORIGINAL turn delivers the answer").isNotBlank();
            assertThat(TestTools.INVOCATIONS).contains("it_slow:s", "it_echo:e");

            var done = history(c.runtime, c.conversationId);
            var resultsPerCall = ofType(done, MessageType.TOOL_RESULT).stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            m -> String.valueOf(m.metadata().get("callId"))));
            resultsPerCall.forEach((callId, results) ->
                    assertThat(results).as("exactly one result per call").hasSize(1));
            int maxRun = done.messages().stream().mapToInt(Message::runOrZero).max().orElse(0);
            assertThat(maxRun).as("no resume execution").isZero();
        }
    }

    private static boolean slowStillRunning(Chat chat) {
        var history = history(chat.runtime, chat.conversationId);
        var results = ofType(history, MessageType.TOOL_RESULT).stream()
                .map(m -> String.valueOf(m.metadata().get("callId"))).toList();
        return ofType(history, MessageType.TOOL_DISPATCHED).stream()
                .anyMatch(m -> !results.contains(String.valueOf(m.metadata().get("callId"))));
    }

    @Test
    void cancelMidToolWritesAStubAndTheLateResultIsDiscarded() {
        try (Chat chat = openChat()) {
            TestTools.slowMillis = 6_000;
            ChatTurnHandle handle = chat.service.submitChat(chat.session.id(),
                    "Call the tool it_slow with text='x'.", event -> { });

            boolean dispatched = await(chat.runtime, chat.conversationId,
                    h -> !ofType(h, MessageType.TOOL_DISPATCHED).isEmpty()
                            && ofType(h, MessageType.TOOL_RESULT).isEmpty(),
                    Duration.ofSeconds(60));
            assumeTrue(dispatched, "model did not dispatch it_slow in time");

            assertThat(chat.service.cancelChat(chat.session.id())).isTrue();

            var afterCancel = history(chat.runtime, chat.conversationId);
            var results = ofType(afterCancel, MessageType.TOOL_RESULT);
            assertThat(results).as("cancel writes the stub synchronously").hasSize(1);
            assertThat(results.get(0).content()).contains("Cancelled by user");

            assertThatThrownBy(() -> handle.result().join())
                    .as("the turn ends as cancelled, not with an answer")
                    .matches(t -> t instanceof java.util.concurrent.CancellationException
                            || t.getCause() instanceof java.util.concurrent.CancellationException);

            // The slow tool finishes its sleep AFTER the stub — the worker's
            // post-execution checks must discard that late output.
            try {
                Thread.sleep(TestTools.slowMillis + 2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var later = ofType(history(chat.runtime, chat.conversationId), MessageType.TOOL_RESULT);
            assertThat(later).as("still exactly one result — the late tool output was discarded").hasSize(1);
            assertThat(later.get(0).content()).contains("Cancelled by user");
        }
    }
}
