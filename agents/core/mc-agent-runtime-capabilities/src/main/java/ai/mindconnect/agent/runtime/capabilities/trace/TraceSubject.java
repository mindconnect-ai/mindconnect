package ai.mindconnect.agent.runtime.capabilities.trace;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;

/** Whose trace it is: what traces are filtered by. */
public record TraceSubject(SessionId sessionId, TurnId turnId, String agent) { }
