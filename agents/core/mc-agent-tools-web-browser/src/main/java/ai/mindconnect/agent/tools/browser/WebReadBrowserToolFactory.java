package ai.mindconnect.agent.tools.browser;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

public final class WebReadBrowserToolFactory implements ToolFactory {

    @Override public String name() { return WebReadBrowserTool.NAME; }

    @Override public String group() { return "web"; }

    @Override
    public void bind(ToolEnvironment env) {
        // Nothing to wire: the Playwright singleton starts on first use.
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new WebReadBrowserTool();
    }
}
