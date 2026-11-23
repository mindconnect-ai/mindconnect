package ai.mindconnect.agent.sdk.inmemory;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import ai.mindconnect.agent.runtime.api.domain.Session;

/** Skills built in code and handed over as a list. */
public final class InMemorySkillResolver implements SkillResolver {

    private final List<SkillDefinition> skills;

    public InMemorySkillResolver(List<SkillDefinition> skills) {
        this.skills = List.copyOf(skills);
    }

    @Override
    public List<SkillSummary> skills(Session session) {
        return skills.stream().map(skill -> new SkillSummary(skill.name(), skill.description())).toList();
    }

    @Override
    public Optional<SkillDefinition> resolve(String skillName, Session session) {
        return skills.stream().filter(skill -> skill.name().equals(skillName)).findFirst();
    }
}
