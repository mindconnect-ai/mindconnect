package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.List;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * The agents a project brings with it — a reviewer that knows this
 * codebase's rules — beside the ones an installation keeps. An agent that
 * delegates at all may call them without naming them: they come from the
 * directory whose instructions it is already following.
 *
 * <p>What makes that safe is the source's to keep: such an agent may do no
 * more than the agent calling it, or opening someone else's repository would
 * hand it tools the caller was never given.
 */
public interface ProjectAgentSource {

    /**
     * @param caller the agent that would hand them a task; what they may do
     *               is cut from what it may do
     * @return the project's agents for this session, none when it has none
     */
    List<AgentDefinition> agents(AgentDefinition caller, Session session);
}
