package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.domain.TurnStatus;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.tool.ToolCallScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An agent-call step asks as the run's user, and never waits for good. */
class WorkflowAgentCallsTest {

    static final AgentId WRITER = AgentId.random();

    /** A turn that has answered — or will, or never does. */
    static final class Turn implements ChatTurnHandle {
        final CompletableFuture<TurnResult> outcome;
        boolean cancelled;

        Turn(CompletableFuture<TurnResult> outcome) {
            this.outcome = outcome;
        }

        @Override public ChatTurnId id() { return ChatTurnId.random(); }
        @Override public SessionId sessionId() { return SessionId.random(); }
        @Override public TurnStatus status() { return TurnStatus.COMPLETED; }
        @Override public CompletableFuture<String> result() { return outcome.thenApply(TurnResult::text); }
        @Override public CompletableFuture<TurnResult> outcome() { return outcome; }
        @Override public boolean cancel() { cancelled = true; return true; }
    }

    final List<UserId> openedFor = new ArrayList<>();

    WorkflowAgentCalls calls(Turn turn, Duration timeout) {
        return new WorkflowAgentCalls(
                name -> name.equals("writer") ? Optional.of(WRITER) : Optional.empty(),
                (agent, user) -> { openedFor.add(user); return SessionId.random(); },
                (session, message) -> turn,
                timeout);
    }

    static Turn answered(String text) {
        return new Turn(CompletableFuture.completedFuture(TurnResult.completed(ChatTurnId.random(), text)));
    }

    @Test
    void the_agent_is_asked_as_the_user_the_workflow_runs_for() {
        String answer = ToolCallScope.detached(UserId.of("anna"))
                .runWith(() -> calls(answered("Done."), Duration.ofSeconds(5)).call("writer", "Write it."));
        assertThat(answer).isEqualTo("Done.");
        assertThat(openedFor).containsExactly(UserId.of("anna"));
    }

    @Test
    void a_run_on_nobodys_behalf_asks_as_the_workflow_user() {
        calls(answered("ok"), Duration.ofSeconds(5)).call("writer", "Write it.");
        assertThat(openedFor).containsExactly(UserId.of(WorkflowAgentCalls.WORKFLOW_USER));
    }

    @Test
    void a_turn_that_stops_for_an_approval_fails_the_step_instead_of_blocking_it() {
        var approval = new ToolApproval("r", "c", "mail_send", "{}", SessionId.random(), SessionId.random(),
                null, java.time.Instant.now());
        Turn turn = new Turn(CompletableFuture.completedFuture(
                TurnResult.incomplete(ChatTurnId.random(), List.of(approval))));
        assertThatThrownBy(() -> calls(turn, Duration.ofSeconds(5)).call("writer", "Send it."))
                .hasMessageContaining("approval of mail_send");
        assertThat(turn.cancelled).isTrue();
    }

    @Test
    void a_turn_that_does_not_answer_in_time_is_cancelled() {
        Turn turn = new Turn(new CompletableFuture<>());
        assertThatThrownBy(() -> calls(turn, Duration.ofMillis(50)).call("writer", "Think."))
                .hasMessageContaining("gave no answer");
        assertThat(turn.cancelled).isTrue();
    }

    @Test
    void an_unknown_agent_is_named() {
        assertThatThrownBy(() -> calls(answered("x"), Duration.ofSeconds(1)).call("nobody", "Hi"))
                .hasMessageContaining("No agent named 'nobody'");
    }
}
