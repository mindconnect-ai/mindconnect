package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.setup.ToolBundles;
import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.ToolBundleFormComponent;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.ui.model.UiPage;

/** The "add a group of tools" form of an agent. */
public final class ToolBundleFormPage extends AdminPage {

    private final AgentDefinition agent;
    private final ToolBundles bundles;

    public ToolBundleFormPage(AgentDefinition agent, ToolBundles bundles) {
        this.agent = agent;
        this.bundles = bundles;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/agents/" + agent.id().value() + "/tools/new-group",
                new ToolBundleFormComponent(agent, bundles).render());
    }
}
