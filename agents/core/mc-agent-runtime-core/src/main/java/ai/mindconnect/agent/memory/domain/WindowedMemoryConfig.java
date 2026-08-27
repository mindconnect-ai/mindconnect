package ai.mindconnect.agent.memory.domain;

/**
 * Sliding-window memory: keep the most recent {@code windowSize} messages, drop the rest.
 * No summarization, no tool-result compression.
 */
public record WindowedMemoryConfig(int windowSize) implements MemoryConfig {

    public static final int DEFAULT_WINDOW_SIZE = 20;
    public static final WindowedMemoryConfig DEFAULT = new WindowedMemoryConfig(DEFAULT_WINDOW_SIZE);

    public WindowedMemoryConfig {
        if (windowSize < 1)
            throw new IllegalArgumentException("windowSize must be >= 1: " + windowSize);
    }

    @Override
    public String kind() { return "windowed"; }
}
