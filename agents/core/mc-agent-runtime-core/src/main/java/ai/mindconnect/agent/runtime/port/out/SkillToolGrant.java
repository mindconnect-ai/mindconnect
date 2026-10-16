package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.tool.AgentTool;

import java.util.Optional;

/**
 * Hands an agent the {@link AgentDefinition#SKILL_TOOL skill tool} by its
 * skills setting rather than by its tool list, so the runtime offers the tool
 * without knowing where skills come from.
 */
@FunctionalInterface
public interface SkillToolGrant {

    /** The tool's binding for this agent in this session; empty when it has no skill to load. */
    Optional<AgentTool> skillTool(AgentDefinition def, AgentSession session);

    /** No skills anywhere: the tool is never offered. */
    static SkillToolGrant none() {
        return (def, session) -> Optional.empty();
    }
}
