package ai.mindconnect.agent.tools.workflow.step;

/**
 * Process-wide holder for the {@link AgentInvoker}. The workflow engine builds
 * step instances via ServiceLoader ({@code SpiWorkflowContextFactory}), which
 * has no access to the Spring context — so the host app's auto-configuration
 * publishes its invoker here at startup, and {@link AgentCallStep} reads it at
 * execution time.
 */
public final class AgentInvokers {

    private static volatile AgentInvoker invoker;

    private AgentInvokers() {}

    /** Publishes the invoker; called by the host's auto-configuration. */
    public static void set(AgentInvoker value) {
        invoker = value;
    }

    /** For tests. */
    public static void clear() {
        invoker = null;
    }

    /** The current invoker, or a descriptive failure when none is published. */
    static AgentInvoker require() {
        AgentInvoker current = invoker;
        if (current == null) {
            throw new IllegalStateException(
                    "No AgentInvoker is available — agent-call steps need the agent runtime "
                    + "(is this workflow running inside an agent host application?)");
        }
        return current;
    }
}
