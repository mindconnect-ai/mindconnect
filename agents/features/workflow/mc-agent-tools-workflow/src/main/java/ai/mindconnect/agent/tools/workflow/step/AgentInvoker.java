package ai.mindconnect.agent.tools.workflow.step;

/**
 * The seam between the workflow engine and the agent runtime: sends one
 * message to a named agent and blocks until the final answer is available.
 * Implemented by the host application (see the module's auto-configuration);
 * steps reach it through {@link AgentInvokers} because step instances are
 * created by the ServiceLoader-driven workflow context factory, outside any
 * Spring context.
 */
public interface AgentInvoker {

    /**
     * Runs one full chat turn against the agent named {@code agentName} in a
     * fresh session and returns the agent's final answer.
     *
     * @throws RuntimeException when the agent doesn't exist or the turn fails
     */
    String call(String agentName, String message);

    /**
     * Creates or updates (by {@code name}) an agent definition from an inline
     * spec — see {@code AgentCallData#getAgentSpec()} for the shape. Returns
     * the agent name to call. Optional capability: the default throws.
     */
    default String upsertAgent(java.util.Map<String, Object> spec) {
        throw new UnsupportedOperationException("This runtime does not support inline agent specs");
    }
}
