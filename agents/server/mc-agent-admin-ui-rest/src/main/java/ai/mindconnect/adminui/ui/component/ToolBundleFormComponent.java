package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.setup.ToolBundles;
import ai.mindconnect.adminui.ui.controller.AgentUiController;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiNode;

import java.util.ArrayList;
import java.util.List;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * Adds tools to an agent by the set: a group, a subgroup, everything on one
 * connection, or several single tools at once. Each lands as its own row, so
 * the Tools table's Remove is how one is taken out again.
 */
public final class ToolBundleFormComponent implements UiComponent {

    private final AgentDefinition agent;
    private final ToolBundles bundles;

    public ToolBundleFormComponent(AgentDefinition agent, ToolBundles bundles) {
        this.agent = agent;
        this.bundles = bundles;
    }

    @Override
    public String id() {
        return "tool-bundle-" + agent.id().value();
    }

    @Override
    public UiNode render() {
        List<UiField.Option> options = new ArrayList<>();
        bundles.all().forEach(b -> options.add(UiField.Option.of(b.key(), b.label())));
        String backHref = "/admin/agents/" + agent.id().value() + "?section=tools";

        return UiForm.of(id(), "Add a group of tools")
                .field(UiField.multiselect("bundles", "Tools", List.of(), options)
                        .asEditable().asRequired()
                        .hint("A group adds every tool in it; a single tool adds just that one. Tools the "
                                + "agent already has are left as they are. Take single ones out again in "
                                + "the Tools table."))
                .field(UiField.bool("needsApproval", "Needs approval", false).asEditable()
                        .hint("A human must approve every call of these tools before it runs."))
                .field(UiField.bool("deferred", "Deferred (via tool search only)", false).asEditable()
                        .hint("Not offered to the LLM up front — the agent finds them with tool_search."))
                .action(UiAction.primary("save", "Add").icon("add")
                        .onClick(trigger(on(AgentUiController.class).addToolBundle(agent.id().value(), null, null), id())))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel").dispatch("GET", backHref))
                .link(UiLink.of("back", backHref, "← Back to Agent"));
    }
}
