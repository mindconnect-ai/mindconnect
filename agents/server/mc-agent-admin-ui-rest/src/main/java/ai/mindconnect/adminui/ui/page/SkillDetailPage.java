package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.SkillDetailComponent;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.ui.model.UiPage;

/** Read-only detail page for one skill. */
public final class SkillDetailPage extends AdminPage {

    private final Skill skill;

    public SkillDetailPage(Skill skill) {
        this.skill = skill;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/skills/" + skill.id().value(),
                new SkillDetailComponent(skill).render());
    }
}
