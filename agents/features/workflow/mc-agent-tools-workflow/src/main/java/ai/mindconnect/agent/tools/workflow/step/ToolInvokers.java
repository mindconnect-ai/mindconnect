package ai.mindconnect.agent.tools.workflow.step;

/**
 * Process-wide holder for the {@link ToolInvoker} — same pattern and reasoning
 * as {@link AgentInvokers}: the workflow's step instances are built via
 * ServiceLoader, so the host app's auto-configuration publishes the invoker
 * here at startup.
 */
public final class ToolInvokers {

    private static volatile ToolInvoker invoker;

    private ToolInvokers() {}

    /** Publishes the invoker; called by the host's auto-configuration. */
    public static void set(ToolInvoker value) {
        invoker = value;
    }

    /** For tests. */
    public static void clear() {
        invoker = null;
    }

    /** The current invoker, or {@code null} when no agent host is running — for editors. */
    public static ToolInvoker getOrNull() {
        return invoker;
    }

    /** The current invoker, or a descriptive failure when none is published. */
    static ToolInvoker require() {
        ToolInvoker current = invoker;
        if (current == null) {
            throw new IllegalStateException(
                    "No ToolInvoker is available — tool-call steps need the agent runtime "
                    + "(is this workflow running inside an agent host application?)");
        }
        return current;
    }
}
