package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.ToolDetailComponent;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.ui.model.UiPage;

/**
 * Read-only tool detail page reached from the tool table's View
 * action. Wraps a {@link ToolDetailComponent}.
 */
public final class ToolDetailPage extends AdminPage {

    private final ManagedAgent agent;
    private final AgentTool tool;
    private final ToolRegistry toolRegistry;
    private final JsonMapperFactory json;

    public ToolDetailPage(ManagedAgent agent, AgentTool tool, ToolRegistry toolRegistry, JsonMapperFactory json) {
        this.agent = agent;
        this.tool = tool;
        this.toolRegistry = toolRegistry;
        this.json = json;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/agents/" + agent.id().value() + "/tools/" + tool.id().value(),
                new ToolDetailComponent(agent, tool, toolRegistry, json).render());
    }
}
