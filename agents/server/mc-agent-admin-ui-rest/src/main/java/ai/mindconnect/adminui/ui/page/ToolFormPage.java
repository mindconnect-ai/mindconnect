package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.ToolFormComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.ui.model.UiPage;

/**
 * Tool create/edit form for an agent. Same page for both modes — the
 * wrapped {@link ToolFormComponent} accepts {@code null} as the
 * "new tool" marker.
 */
public final class ToolFormPage extends AdminPage {

    private final AgentDefinition agent;
    private final AgentTool tool;
    private final ToolRegistry toolRegistry;

    /**
     * @param tool {@code null} for the new-tool form, a populated
     *             {@link AgentTool} for the edit form
     */
    public ToolFormPage(AgentDefinition agent, AgentTool tool, ToolRegistry toolRegistry) {
        this.agent = agent;
        this.tool = tool;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public UiPage render() {
        String url = tool == null
                ? "/admin/agents/" + agent.id() + "/tools/new"
                : "/admin/agents/" + agent.id() + "/tools/" + tool.id() + "/edit";
        return UiPage.of(url,
                new ToolFormComponent(agent, tool, toolRegistry).render());
    }
}
