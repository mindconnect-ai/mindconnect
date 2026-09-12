package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.controller.SkillUiController;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * Create/edit form for a stored skill. Used for both modes — {@code null}
 * is the new-skill marker, as in the agent form.
 *
 * <p>The description field carries the weight here, and its hint says so:
 * it is the one line the model sees before it decides whether to load the
 * skill, so it has to say WHEN to use it, not what the instructions contain.
 */
public final class SkillFormComponent implements UiComponent {

    private final Skill skill;

    public SkillFormComponent(Skill skill) {
        this.skill = skill;
    }

    @Override
    public String id() {
        return skill == null ? "skill-new" : "skill-" + skill.id().value();
    }

    @Override
    public UiNode render() {
        boolean isNew = skill == null;

        var header = UiList.of(id() + "-header",
                        isNew ? "New Skill" : "Edit Skill: " + skill.name())
                .icon("graduation-cap");

        var form = UiForm.of(id(), null)
                .field(UiField.text("name", "Name", isNew ? null : skill.name())
                        .asEditable().asRequired()
                        .placeholder("weekly-report")
                        .hint("What the model names in the skill tool: lower-case letters, "
                                + "digits and dashes"))
                // The version this form was opened with. Hidden, but submitted like
                // every named input: the save is refused if the skill was saved since.
                .field(UiField.text("version", "Version",
                                isNew || skill.version() == null ? "0" : skill.version().toString())
                        .asEditable().<UiField>hidden())
                .field(UiField.text("description", "Description", isNew ? null : skill.description())
                        .asEditable().asRequired()
                        .placeholder("Use when writing the weekly status report for a customer")
                        .hint("The one line the model decides on — say WHEN to reach for this "
                                + "skill. It is in the prompt from the first token; the "
                                + "instructions below are not"))
                .field(UiField.textarea("instructions", "Instructions",
                                isNew ? null : skill.instructions())
                        .asEditable().asRequired()
                        .hint("Markdown, handed over whole when the model loads the skill. "
                                + "Write it as long as the work needs — it costs nothing until "
                                + "it is used"))
                .field(UiField.text("tools", "Tools it expects",
                                isNew ? null : String.join(", ", skill.tools()))
                        .asEditable()
                        .placeholder("file_read, vector_search")
                        .hint("Named to the model when the skill is loaded. This grants nothing: "
                                + "the agent's own tools still decide what it may call"))
                .field(UiField.bool("enabled", "Enabled", isNew || skill.enabled())
                        .asEditable()
                        .hint("A skill switched off is offered to no agent, and stays here"))
                .action(UiAction.primary("save", "Save").icon("save")
                        .onClick(isNew
                                ? trigger(on(SkillUiController.class).create(null, null), id())
                                : trigger(on(SkillUiController.class).update(skill.id().value(), null, null), id())))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                        .onClick(isNew
                                ? trigger(on(SkillUiController.class).list(null))
                                : trigger(on(SkillUiController.class).detail(skill.id().value(), null))))
                .link(UiLink.of("back", "/admin/skills", "← Back to Skills"));

        return UiStack.of(id() + "-page").child(header).child(form);
    }
}
