package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemorySkillRepository;
import ai.mindconnect.agent.runtime.skill.Skill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A skill is looked up by name, so the API refuses a name no model could type
 * as the caller's mistake and a name another skill already has as a conflict —
 * instead of failing with 500, or storing a second skill nobody can reach.
 */
class SkillApiControllerTest {

    private final InMemorySkillRepository repository = new InMemorySkillRepository();
    private final SkillApiController controller = new SkillApiController(repository);

    private static SkillApiController.SkillRequest request(String name) {
        return new SkillApiController.SkillRequest(name, "Use when releasing", "Tag, then push.",
                List.of(), null, null);
    }

    @Test
    void anUnusableNameIsABadRequest() {
        assertThat(controller.create(request("Weekly Report")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.create(request(null)).getStatusCode().value()).isEqualTo(400);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void aNameAnotherSkillHasIsAConflict_onCreateAndOnRename() {
        assertThat(controller.create(request("release")).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.create(request("Release")).getStatusCode().value()).isEqualTo(409);

        Skill other = (Skill) controller.create(request("hotfix")).getBody();
        assertThat(controller.update(other.id().value(), request("release")).getStatusCode().value())
                .isEqualTo(409);
        assertThat(repository.findAll()).extracting(Skill::name).containsExactly("hotfix", "release");
    }

    @Test
    void savingASkillUnderItsOwnNameIsNoConflict() {
        Skill saved = (Skill) controller.create(request("release")).getBody();

        var updated = controller.update(saved.id().value(), new SkillApiController.SkillRequest(
                null, "Use when cutting a release", null, null, null, null));

        assertThat(updated.getStatusCode().value()).isEqualTo(200);
    }
}
