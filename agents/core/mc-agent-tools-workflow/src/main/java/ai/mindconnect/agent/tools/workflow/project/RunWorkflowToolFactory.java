package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.runtime.service.workflows.ProjectWorkflowFiles;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tools.workflow.step.CallerLimits;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Makes {@code run_workflow}. The runtime binds it by itself when the
 * session's project defines workflows, and the binding names what the caller
 * may use ({@link ProjectWorkflowFiles#CALLER_TOOLS} and its neighbours). A
 * binding made by hand names nothing, and the workflows it runs then reach no
 * tool and no agent — what a file from a repository gets is decided by the
 * agent that runs it, never by the file.
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
        CallerLimits limits = agentTool == null ? CallerLimits.none()
                : new CallerLimits(names(agentTool, ProjectWorkflowFiles.CALLER_TOOLS),
                        names(agentTool, ProjectWorkflowFiles.APPROVAL_TOOLS),
                        names(agentTool, ProjectWorkflowFiles.CALLER_AGENTS));
        return new RunWorkflowTool(scope, limits);
    }

    private static Set<String> names(AgentTool agentTool, String key) {
        Set<String> names = new LinkedHashSet<>();
        if (agentTool.overrides().get(key) instanceof Collection<?> raw) {
            raw.forEach(v -> names.add(String.valueOf(v)));
        }
        return names;
    }
}
