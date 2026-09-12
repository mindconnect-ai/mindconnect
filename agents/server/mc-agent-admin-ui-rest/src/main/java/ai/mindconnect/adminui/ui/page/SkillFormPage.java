package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.SkillFormComponent;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.ui.model.UiPage;

/**
 * Skill create/edit form — one page for both modes, {@code null} being the
 * new-skill marker.
 */
public final class SkillFormPage extends AdminPage {

    private final Skill skill;

    public SkillFormPage(Skill skill) {
        this.skill = skill;
    }

    @Override
    public UiPage render() {
        String url = skill == null
                ? "/admin/skills/new"
                : "/admin/skills/" + skill.id().value() + "/edit";
        return UiPage.of(url, new SkillFormComponent(skill).render());
    }
}
