package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tool.workspace.CommandRunner;
import ai.mindconnect.agent.tool.workspace.WorkspaceProvider;

import java.util.Optional;

public class ProcessListToolFactory implements ToolFactory {

    private Optional<WorkspaceProvider> workspaces = Optional.empty();

    @Override public String name() { return "process_list"; }
    @Override public String group() { return "files"; }

    @Override public void bind(ToolEnvironment env) {
        this.workspaces = env.get(WorkspaceProvider.class);
    }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        Optional<CommandRunner> runner = workspaces.flatMap(provider -> provider.commands(scope, agentTool));
        if (runner.isPresent()) {
            return new RemoteProcessListTool(runner.get());
        }
        return new ProcessListTool(scope == null ? null : scope.sessionId());
    }
}
