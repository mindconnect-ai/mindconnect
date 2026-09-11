package ai.mindconnect.agent.runtime.domain;

/**
 * Input record for creating a new {@link AgentDefinition}.
 *
 * <p>Identity is not part of the spec — the create call assigns it.
 * Validation lives in the
 * use-case ({@code AgentRegistry.create}), not in this record.
 */
public record AgentSpec(
        String name,
        String description,
        String systemPrompt,
        String welcomeMessage,
        String llmConfigName
) {}
