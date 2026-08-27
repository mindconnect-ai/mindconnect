package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.ToolDetailComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.ui.model.UiPage;

/**
 * Read-only tool detail page reached from the tool table's View
 * action. Wraps a {@link ToolDetailComponent}.
 */
public final class ToolDetailPage extends AdminPage {

    private final AgentDefinition agent;
    private final AgentTool tool;
    private final ToolRegistry toolRegistry;

    public ToolDetailPage(AgentDefinition agent, AgentTool tool, ToolRegistry toolRegistry) {
        this.agent = agent;
        this.tool = tool;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public UiPage render() {
        return UiPage.of("/admin/agents/" + agent.id() + "/tools/" + tool.id(),
                new ToolDetailComponent(agent, tool, toolRegistry).render());
    }
}
