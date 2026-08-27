package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.workflow.domain.BaseStepData;

/**
 * A workflow step that sends one message to an agent and yields the agent's
 * final answer as the step result ({@code assignResultToVar} works as usual).
 *
 * <p>The agent runs its full chat turn — tools, sub-agents, reviewers — in a
 * fresh session opened for this call. The message may reference workflow
 * variables ({@code ${var}}), resolved before sending.
 *
 * <p>Type discriminator: {@code agentcall}.
 */
public class AgentCallData extends BaseStepData {

    /** Name of the {@code AgentDefinition} to call. */
    private String agent;

    /**
     * Alternative to {@link #agent}: an inline agent specification the step
     * upserts (by name) before calling — so a workflow can define its own
     * specialist agents. Keys: {@code name}, {@code systemPrompt},
     * {@code llmConfigName}, {@code description}, and {@code tools} — a list
     * of maps with {@code name}, optional {@code tool} (alias target) and
     * {@code overrides} (config + params pins). String values anywhere in the
     * spec are expression-resolved against the workflow scope, so e.g. a
     * store name can flow into a pin: {@code {"params": {"store": "${store}"}}}.
     * Upserting is idempotent per name — a ForEach reuses one definition.
     */
    private java.util.Map<String, Object> agentSpec;

    public java.util.Map<String, Object> getAgentSpec() {
        return agentSpec;
    }

    public void setAgentSpec(java.util.Map<String, Object> agentSpec) {
        this.agentSpec = agentSpec;
    }

    /** The message to send; expression-resolvable ({@code ${var}}). */
    private String message;

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
