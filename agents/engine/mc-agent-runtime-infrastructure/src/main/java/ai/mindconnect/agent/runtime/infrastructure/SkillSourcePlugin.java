package ai.mindconnect.agent.runtime.infrastructure;

import ai.mindconnect.agent.runtime.capabilities.skill.SkillResolver;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Where the skills of an installation come from: the stored ones, each
 * user's own files and the files of the project a session works in.
 */
public final class SkillSourcePlugin implements Plugin {

    private final UserSkillDirectory userSkills;

    public SkillSourcePlugin(UserSkillDirectory userSkills) {
        this.userSkills = userSkills;
    }

    @Override
    public String name() {
        return "skill-sources";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(UserSkillDirectory.class, () -> userSkills);
        registry.contribute(SkillResolver.class,
                new SkillSourceResolver(registry.supplier(SkillRepository.class), userSkills));
    }
}
