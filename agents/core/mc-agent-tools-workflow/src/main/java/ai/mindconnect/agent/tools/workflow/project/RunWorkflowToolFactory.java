package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.runtime.service.workflows.ProjectWorkflowFiles;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tools.workflow.step.CallerLimits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Makes {@code run_workflow}. The runtime binds it by itself when the
 * session's project defines workflows, and the binding names what the caller
 * may use: its tool bindings ({@link ProjectWorkflowFiles#CALLER_TOOLS}) and
 * its callable agents. Without them the workflows reach no tool and no agent —
 * what a file from a repository gets is decided by the agent that runs it,
 * never by the file.
 */
public final class RunWorkflowToolFactory implements ToolFactory {

    @Override
    public String name() {
        return ProjectWorkflowFiles.TOOL;
    }

    @Override
    public String group() {
        return "workflow";
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        if (agentTool == null) {
            return new RunWorkflowTool(scope, CallerLimits.none());
        }
        List<AgentTool> tools = new ArrayList<>();
        if (agentTool.overrides().get(ProjectWorkflowFiles.CALLER_TOOLS) instanceof Collection<?> raw) {
            for (Object entry : raw) {
                AgentTool binding = ProjectWorkflowFiles.fromCallerTool(entry);
                if (binding != null) tools.add(binding);
            }
        }
        Set<String> agents = new LinkedHashSet<>();
        if (agentTool.overrides().get(ProjectWorkflowFiles.CALLER_AGENTS) instanceof Collection<?> raw) {
            raw.forEach(v -> agents.add(String.valueOf(v)));
        }
        return new RunWorkflowTool(scope, CallerLimits.of(tools, agents));
    }
}
