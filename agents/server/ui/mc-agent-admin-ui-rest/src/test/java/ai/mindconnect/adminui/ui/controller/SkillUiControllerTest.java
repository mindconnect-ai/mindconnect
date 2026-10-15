package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemorySkillRepository;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skills screen manages the store, so it lists every stored skill —
 * including one switched off, which an agent can no longer load but which
 * still has to be found here to be switched back on — and it refuses a name
 * another stored skill already carries.
 */
class SkillUiControllerTest {

    private final InMemorySkillRepository repository = new InMemorySkillRepository();
    private final SkillUiController controller = controller(repository);

    private static SkillUiController controller(SkillRepository repository) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("skillRepository", repository);
        beans.addBean("skillCatalog", SkillCatalog.of(repository, SkillCatalog.OFF));
        return new SkillUiController(beans.getBeanProvider(SkillRepository.class),
                beans.getBeanProvider(SkillCatalog.class));
    }

    private static Skill stored(String name, boolean enabled) {
        Skill skill = Skill.create(name, "Use when " + name, "Do it.", List.of());
        return enabled ? skill : skill.withFields(name, skill.description(), skill.instructions(), List.of(), false);
    }

    @Test
    void aSkillSwitchedOffStaysOnTheList() throws Exception {
        repository.save(stored("release", true));
        repository.save(stored("hotfix", false));

        String list = json(controller.list(null));

        assertThat(list).contains("release").contains("hotfix (off)");
    }

    @Test
    void aNameAnotherStoredSkillHasIsRefused_onCreateAndOnRename() throws Exception {
        repository.save(stored("release", true));
        Skill hotfix = repository.save(stored("hotfix", true));

        String created = json(controller.create(Map.of("name", "Release", "description", "d",
                "instructions", "i"), null).getBody());
        assertThat(created).contains("already a skill named 'release'");

        String renamed = json(controller.update(hotfix.id().value(), Map.of("name", "release",
                "description", "d", "instructions", "i"), null).getBody());
        assertThat(renamed).contains("already a skill named 'release'");

        assertThat(repository.findAll()).extracting(Skill::name).containsExactly("hotfix", "release");
    }

    private static String json(Object node) throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(node);
    }
}
