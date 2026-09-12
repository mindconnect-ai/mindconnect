package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.controller.SkillUiController;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * One skill, read-only: what the model is told about it, and the
 * instructions it gets when it loads it. Edit and Delete only for a stored
 * skill — one read off disk is edited in its file.
 *
 * <p>The instructions are rendered as the Markdown they are, in a card of
 * their own below the fields. As a detail field they arrive as one run-on
 * paragraph — headings, numbered steps and code spans flattened — which is
 * unreadable at exactly the length that makes a skill worth writing.
 */
public final class SkillDetailComponent implements UiComponent {

    private final Skill skill;

    public SkillDetailComponent(Skill skill) {
        this.skill = skill;
    }

    @Override
    public String id() {
        return "skill-detail-" + skill.id().value();
    }

    @Override
    public UiNode render() {
        return UiStack.of(id() + "-page")
                .child(fields())
                .child(instructions());
    }

    /** What the model is told about the skill, and what can be done with it. */
    private UiDetail fields() {
        UiDetail detail = UiDetail.of(id(), skill.name())
                .field(UiField.text("name", "Name", skill.name()))
                .field(UiField.text("description", "Description", skill.description()))
                .field(UiField.bool("enabled", "Enabled", skill.enabled()))
                .field(UiField.text("source", "Source", sourceLabel(skill)))
                .field(UiField.text("tools", "Tools it expects",
                        skill.tools().isEmpty() ? "—" : String.join(", ", skill.tools())));
        if (skill.directory() != null) {
            detail.field(UiField.text("directory", "Directory", skill.directory()));
        }
        if (skill.source() == SkillSource.MANAGED) {
            detail.action(UiAction.primary("edit", "Edit").icon("edit")
                            .onClick(trigger(on(SkillUiController.class).editForm(skill.id().value()))))
                    .action(UiAction.danger("delete", "Delete").icon("delete")
                            .confirm("Delete skill '" + skill.name() + "'?")
                            .onClick(trigger(on(SkillUiController.class).delete(skill.id().value(), null))));
        }
        return detail.link(UiLink.of("back", "/admin/skills", "← Back to Skills"));
    }

    /** The instructions themselves, rendered. */
    private UiNode instructions() {
        return UiStack.of(id() + "-instructions")
                .child(UiMarkdown.of(id() + "-instructions-md",
                        "### Instructions\n_What the agent receives when it loads this skill._\n\n"
                                + skill.instructions()));
    }

    /** Where the skill comes from, and for a file one, what that means for editing. */
    static String sourceLabel(Skill skill) {
        return switch (skill.source()) {
            case MANAGED -> "this installation";
            case USER -> "the user's skills directory (read-only here — edit the file)";
            case PROJECT -> "a project's .mindconnect/skills (read-only here — edit the file)";
        };
    }
}
