package ai.mindconnect.agent.runtime.api.hook;

import ai.mindconnect.agent.runtime.api.domain.TurnRequest;

/** Whether a turn may start, and with what. */
public sealed interface TurnAdmission {

    /** @param request the input as it goes into the turn — a guard may have rewritten it */
    record Admitted(TurnRequest request) implements TurnAdmission { }

    /** @param reason what the caller is told */
    record Refused(String reason) implements TurnAdmission { }
}
