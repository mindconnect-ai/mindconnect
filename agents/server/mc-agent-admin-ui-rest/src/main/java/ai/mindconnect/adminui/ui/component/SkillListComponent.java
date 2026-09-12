package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.controller.SkillUiController;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiList;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

import java.util.List;

/**
 * The skills this installation can offer, stored ones first and the files a
 * user or a project keeps after them.
 *
 * <p>A file-borne skill has no actions: it is edited where it lies, in the
 * repository or the home directory it came from, and a Delete button on this
 * screen would promise something the admin UI must not do. Its row says
 * where it came from instead.
 */
public final class SkillListComponent implements UiComponent {

    private final List<Skill> skills;
    private final String query;

    public SkillListComponent(List<Skill> skills, String query) {
        this.skills = skills;
        this.query = query;
    }

    @Override
    public String id() {
        return "skill-list";
    }

    @Override
    public UiList render() {
        var list = UiList.of(id(), "Skills  (" + skills.size() + ")")
                .icon("graduation-cap")
                .headerExtra(searchForm())
                .action(UiAction.primary("create", "New Skill").icon("add")
                        .dispatch("GET", "/admin/api/skills/new"));

        if (skills.isEmpty()) {
            list.item(UiList.Item.of("empty", "No skills yet")
                    .description(query == null || query.isBlank()
                            ? "Create one here, or put a SKILL.md in a project's .mindconnect/skills/."
                            : "Nothing matches \u201C" + query + "\u201D."));
            return list;
        }

        for (Skill skill : skills) {
            UiList.Item item = UiList.Item.of(skill.id().value(), title(skill))
                    .description(description(skill));
            if (skill.source() == SkillSource.MANAGED) {
                item.href("/admin/skills/" + skill.id().value())
                        .action(UiAction.danger("delete", "Delete").icon("delete")
                                .confirm("Delete skill '" + skill.name() + "'?")
                                .dispatch("DELETE", "/admin/api/skills/" + skill.id().value()));
            }
            list.item(item);
        }
        return list;
    }

    /** The name, and for a skill nobody can load right now, why not. */
    private static String title(Skill skill) {
        return skill.enabled() ? skill.name() : skill.name() + " (off)";
    }

    private static String description(Skill skill) {
        String where = switch (skill.source()) {
            case MANAGED -> null;
            case USER -> "from the user's skills directory — edited in its file";
            case PROJECT -> "from a project's .mindconnect/skills — edited in its file";
        };
        if (skill.description().isBlank()) return where;
        return where == null ? skill.description() : skill.description() + " · " + where;
    }

    /** Compact search in the list header: typing + Enter re-renders the list filtered. */
    private UiForm searchForm() {
        String formId = "skill-search";
        UiForm form = UiForm.of(formId, null);
        form.field(UiField.text("q", "", query)
                .asEditable()
                .icon("search")
                .placeholder("Search name or description\u2026")
                .onChange(trigger(on(SkillUiController.class).search(null, null), formId)));
        return form;
    }
}
