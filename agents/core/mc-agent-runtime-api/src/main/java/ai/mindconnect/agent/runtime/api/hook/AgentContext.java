package ai.mindconnect.agent.runtime.api.hook;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.TurnId;

/** For whom and in which turn a hook runs. */
public record AgentContext(Session session, AgentDefinition agent, TurnId turnId) { }
