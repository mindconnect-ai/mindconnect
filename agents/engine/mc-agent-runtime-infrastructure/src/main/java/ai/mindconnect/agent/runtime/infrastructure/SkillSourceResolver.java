package ai.mindconnect.agent.runtime.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillSummary;
import ai.mindconnect.agent.skill.domain.FileSkills;
import ai.mindconnect.agent.skill.domain.Skill;
import ai.mindconnect.agent.skill.port.out.SkillRepository;

/**
 * The skills an agent may load in a session, from three places: the ones
 * the installation stores, the user's own files, and the files of the
 * project the session works in ({@code .mindconnect/skills}). Read anew for
 * every call, so an edited skill holds from the next turn on.
 *
 * <p>Two skills of the same name are one skill: the more specific source
 * wins, project over user over stored. A project that disagrees with the
 * installation about how its reports are written is right about its own
 * reports, which is the whole reason its file is there.
 */
final class SkillSourceResolver implements SkillResolver {

    private final Supplier<SkillRepository> stored;
    private final UserSkillDirectory userSkills;

    SkillSourceResolver(Supplier<SkillRepository> stored, UserSkillDirectory userSkills) {
        this.stored = stored;
        this.userSkills = userSkills;
    }

    @Override
    public List<SkillSummary> skills(Session session) {
        return all(session).stream().map(skill -> new SkillSummary(skill.name(), skill.description())).toList();
    }

    @Override
    public Optional<SkillDefinition> resolve(String skillName, Session session) {
        return all(session).stream().filter(skill -> skill.name().equals(skillName)).findFirst()
                .map(skill -> new SkillDefinition(skill.name(), skill.description(), skill.instructions(),
                        skill.tools()));
    }

    private List<Skill> all(Session session) {
        Map<String, Skill> byName = new LinkedHashMap<>();
        stored.get().findAll().stream().filter(Skill::enabled).forEach(skill -> byName.put(skill.name(), skill));
        userSkills.skills(session.userId() == null ? null : session.userId().value())
                .forEach(skill -> byName.put(skill.name(), skill));
        if (session.metadata().get(ManagedSession.WORKING_DIRECTORY) instanceof String working
                && !working.isBlank()) {
            FileSkills.project(working).forEach(skill -> byName.put(skill.name(), skill));
        }
        List<Skill> skills = new ArrayList<>(byName.values());
        skills.sort(java.util.Comparator.comparing(Skill::name));
        return List.copyOf(skills);
    }
}
