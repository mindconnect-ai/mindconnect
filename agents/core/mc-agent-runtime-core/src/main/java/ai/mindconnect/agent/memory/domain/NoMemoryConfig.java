package ai.mindconnect.agent.memory.domain;

/**
 * No memory at all — every chat call sees only the system prompt and the current user message.
 * Suitable for one-shot stateless utility agents (title generation, single-prompt completion).
 */
public record NoMemoryConfig() implements MemoryConfig {

    public static final NoMemoryConfig DEFAULT = new NoMemoryConfig();

    @Override
    public String kind() { return "none"; }
}
