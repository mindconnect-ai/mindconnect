package ai.mindconnect.agent.runtime.capabilities.skill;

import java.util.List;

/**
 * A skill in full: what the agent reads once it has loaded it.
 *
 * @param allowedTools the tools the skill needs, by name
 */
public record SkillDefinition(String name, String description, String instructions, List<String> allowedTools) {

    public SkillDefinition {
        allowedTools = List.copyOf(allowedTools);
    }
}
