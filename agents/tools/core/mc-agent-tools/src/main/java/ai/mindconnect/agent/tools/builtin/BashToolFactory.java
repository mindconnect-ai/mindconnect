package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.CommandRunner;

import java.util.Optional;

public final class BashToolFactory extends FileRootedToolFactory {
    @Override public String name() { return "bash"; }

    @Override public String group() { return "files"; }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        // A bound workspace provider that runs commands: bash goes into its container.
        Optional<CommandRunner> runner = workspaces.flatMap(provider -> provider.commands(scope, agentTool));
        if (runner.isPresent()) {
            return new RemoteBashTool(runner.get());
        }
        return new BashTool(BaseDirs.roots(scope, agentTool, defaultBaseDir), scope == null ? null : scope.sessionId());
    }
}
