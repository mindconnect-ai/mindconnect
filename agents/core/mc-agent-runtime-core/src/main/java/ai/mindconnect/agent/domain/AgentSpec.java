package ai.mindconnect.agent.domain;

/**
 * Input record for creating a new {@link AgentDefinition}.
 *
 * <p>Namespace and identity are not part of the spec — they are bound to the
 * {@code AgentRegistry} that owns the create call. Validation lives in the
 * use-case ({@code AgentRegistry.create}), not in this record.
 */
public record AgentSpec(
        String name,
        String description,
        String systemPrompt,
        String welcomeMessage,
        String llmConfigName
) {}
