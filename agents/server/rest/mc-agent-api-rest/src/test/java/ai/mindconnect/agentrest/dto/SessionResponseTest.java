package ai.mindconnect.agentrest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agentrest.SessionFixture;

/**
 * A client that opens while a turn runs has heard none of its events: the
 * session itself has to say that it is busy.
 */
class SessionResponseTest {

    private final ManagedSession session = new SessionFixture().open("alice", null);

    @Test
    void a_session_with_a_turn_underway_names_it_and_where_it_stands() {
        Turn asking = new Turn(TurnId.of("t1"), null, session.id(), null, TurnStatus.INTERRUPTED, List.of(), null,
                null);

        SessionResponse answered = SessionResponse.of(session, Optional.of(asking));

        assertThat(answered.turnId()).isEqualTo("t1");
        assertThat(answered.turnStatus()).isEqualTo("INTERRUPTED");
    }

    @Test
    void an_idle_session_names_no_turn() {
        SessionResponse answered = SessionResponse.of(session, Optional.empty());

        assertThat(answered.turnId()).isNull();
        assertThat(answered.turnStatus()).isNull();
    }
}
