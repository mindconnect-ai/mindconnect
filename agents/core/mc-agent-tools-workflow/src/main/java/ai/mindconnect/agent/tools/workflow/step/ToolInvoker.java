package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.agent.tool.ToolCallScope;

import java.util.Map;
import java.util.Set;

/**
 * The seam between the workflow engine and the runtime's tool registry:
 * executes one tool by name. Implemented by the host application (see the
 * module's auto-configuration); steps reach it through {@link ToolInvokers}
 * because step instances are created outside any Spring context.
 */
public interface ToolInvoker {

    /**
     * Resolves and executes the tool named {@code toolName}.
     *
     * @throws RuntimeException when the tool doesn't exist or cannot be resolved
     */
    String call(String toolName, Map<String, Object> arguments);

    /**
     * Resolves and executes the tool on behalf of {@code scope} — the user,
     * session and agent the workflow runs for, as the host put it on the run
     * ({@link ai.mindconnect.workflow.execution.WorkflowContext#getAttribute(Class)}).
     * {@code null} when the run was started for nobody in particular. A host
     * whose tools depend on the caller overrides this; the default ignores the
     * scope.
     */
    default String call(String toolName, Map<String, Object> arguments, ToolCallScope scope) {
        return call(toolName, arguments);
    }

    /** The registry's current tool names — used by editors for a picker. */
    default Set<String> knownToolNames() {
        return Set.of();
    }
}
