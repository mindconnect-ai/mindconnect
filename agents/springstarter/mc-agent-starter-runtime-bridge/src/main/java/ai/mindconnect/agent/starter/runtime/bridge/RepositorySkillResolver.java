package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillRepository;

/** The skills of the application's repository that are switched on. */
final class RepositorySkillResolver implements SkillResolver {

    private final SkillRepository skills;

    RepositorySkillResolver(SkillRepository skills) {
        this.skills = skills;
    }

    @Override
    public List<SkillSummary> skills(Session session) {
        return skills.findAll().stream()
                .filter(Skill::enabled)
                .map(skill -> new SkillSummary(skill.name(), skill.description()))
                .toList();
    }

    @Override
    public Optional<SkillDefinition> resolve(String skillName, Session session) {
        return skills.findByName(skillName)
                .filter(Skill::enabled)
                .map(skill -> new SkillDefinition(skill.name(), skill.description(), skill.instructions(),
                        skill.tools()));
    }
}
