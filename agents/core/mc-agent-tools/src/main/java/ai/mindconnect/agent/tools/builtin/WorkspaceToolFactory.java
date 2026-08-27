package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;

abstract class WorkspaceToolFactory implements ToolFactory {
    protected WorkspaceStore workspaceStore;

    @Override public void bind(ToolEnvironment env) {
        this.workspaceStore = env.require(WorkspaceStore.class);
    }
}
