package ai.mindconnect.agentrest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** A session's turn is addressed by the session, an approval by its tool call. */
class SessionTurnsTest {

    private static final SessionId SESSION = SessionId.of("s1");
    private static final TurnId TURN = TurnId.of("t1");

    private final TurnService turns = mock(TurnService.class);
    private final SessionTurns sessionTurns = new SessionTurns(providing(turns),
            providing(mock(SessionStore.class)), providing(mock(SessionManagement.class)));

    @Test
    void an_approval_is_answered_on_the_interruption_of_its_tool_call() {
        when(turns.runningTurn(SESSION)).thenReturn(Optional.of(waitingFor("i1", "c1")));

        assertThat(sessionTurns.answerApproval(SESSION, "c1", true, false)).contains(TURN);

        verify(turns).resume(TURN, List.of(InterruptionAnswer.approve("i1")));
    }

    @Test
    void a_card_nothing_waits_for_is_stale() {
        when(turns.runningTurn(SESSION)).thenReturn(Optional.of(waitingFor("i1", "c1")));

        assertThat(sessionTurns.answerApproval(SESSION, "unknown", true, false)).isEmpty();

        verify(turns, never()).resume(any(), any());
    }

    @Test
    void a_session_without_a_running_turn_has_nothing_to_cancel() {
        when(turns.runningTurn(SESSION)).thenReturn(Optional.empty());

        assertThat(sessionTurns.cancel(SESSION)).isFalse();
        assertThat(sessionTurns.open(SESSION)).isEmpty();
    }

    private static Turn waitingFor(String interruptionId, String callId) {
        Interruption interruption = new Interruption(interruptionId, TURN, Interruption.TOOL_APPROVAL,
                new ToolCall(callId, "bash", Map.of(), TURN, SESSION), Map.of());
        return new Turn(TURN, null, SESSION, null, TurnStatus.INTERRUPTED, List.of(interruption), null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providing(T object) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(object);
        return provider;
    }
}
