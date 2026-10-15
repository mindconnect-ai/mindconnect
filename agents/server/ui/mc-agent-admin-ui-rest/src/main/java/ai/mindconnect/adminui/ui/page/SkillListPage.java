package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.SkillListComponent;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.ui.model.UiPage;

import java.util.List;

/** The skills at {@code /admin/skills}. */
public final class SkillListPage extends AdminPage {

    private final List<Skill> skills;
    private final String query;

    public SkillListPage(List<Skill> skills) {
        this(skills, null);
    }

    public SkillListPage(List<Skill> skills, String query) {
        this.skills = skills;
        this.query = query;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/skills", new SkillListComponent(skills, query).render());
    }
}
