package ai.mindconnect.agent.runtime.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import ai.mindconnect.agent.skill.domain.Skill;
import ai.mindconnect.agent.skill.port.out.SkillRepository;

/** The skills of the application's repository that are switched on. */
final class RepositorySkillResolver implements SkillResolver {

    private final Supplier<SkillRepository> stored;

    RepositorySkillResolver(Supplier<SkillRepository> stored) {
        this.stored = stored;
    }

    @Override
    public List<SkillSummary> skills(Session session) {
        return stored.get().findAll().stream()
                .filter(Skill::enabled)
                .map(skill -> new SkillSummary(skill.name(), skill.description()))
                .toList();
    }

    @Override
    public Optional<SkillDefinition> resolve(String skillName, Session session) {
        return stored.get().findByName(skillName)
                .filter(Skill::enabled)
                .map(skill -> new SkillDefinition(skill.name(), skill.description(), skill.instructions(),
                        skill.tools()));
    }
}
