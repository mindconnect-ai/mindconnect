package ai.mindconnect.agent.port.in;

import java.util.Map;

/**
 * Executes a stateless LLM task — no session, no memory, no history.
 * <p>
 * Looks up a named {@code AgentDefinition} to resolve the LLM config and system prompt.
 * Falls back to a globally configured default stateless agent if no matching definition
 * is found.
 * <p>
 * Use this for internal utility calls: summarization, title generation,
 * guardrail checks, classification, etc.
 */
public interface AgentTaskRunner {

    /**
     * Runs the task with the given user message.
     * The system prompt is rendered through the {@link ai.mindconnect.agent.port.out.PromptRenderer} pipeline,
     * giving access to all standard variables ({@code current_date}, etc.).
     *
     * @param task        logical task name — matches an AgentDefinition by name if one exists
     * @param userMessage the input to send to the LLM
     * @return the LLM response text
     */
    String run(String task, String userMessage);

    /**
     * Runs the task with extra prompt-template variables that are added on top of
     * the standard {@link ai.mindconnect.agent.port.out.PromptContextProvider} context.
     * Useful for hook-style sub-agents (response reviewers, classifiers) that need
     * to receive caller-provided values like the user's question or the agent's
     * draft answer in their system prompt.
     * <p>
     * Default implementation ignores {@code extraVars} for backward compatibility —
     * adapters that support templating should override this.
     */
    default String run(String task, String userMessage, Map<String, Object> extraVars) {
        return run(task, userMessage);
    }
}
