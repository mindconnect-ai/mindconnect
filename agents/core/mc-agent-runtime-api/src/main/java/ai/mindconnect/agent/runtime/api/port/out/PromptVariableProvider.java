package ai.mindconnect.agent.runtime.api.port.out;

import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;

/** Contributes values instructions may refer to: the date, the user, the working directory. */
public interface PromptVariableProvider {

    Map<String, Object> variables(AgentDefinition agent, Session session);
}
