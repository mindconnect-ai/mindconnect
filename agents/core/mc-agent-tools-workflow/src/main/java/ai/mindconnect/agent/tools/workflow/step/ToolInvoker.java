package ai.mindconnect.agent.tools.workflow.step;

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

    /** The registry's current tool names — used by editors for a picker. */
    default Set<String> knownToolNames() {
        return Set.of();
    }
}
