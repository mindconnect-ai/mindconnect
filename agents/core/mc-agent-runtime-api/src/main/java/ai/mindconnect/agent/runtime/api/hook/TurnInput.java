package ai.mindconnect.agent.runtime.api.hook;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;

/** What someone wants a turn for, before the turn exists. */
public record TurnInput(Session session, TurnRequest request) { }
