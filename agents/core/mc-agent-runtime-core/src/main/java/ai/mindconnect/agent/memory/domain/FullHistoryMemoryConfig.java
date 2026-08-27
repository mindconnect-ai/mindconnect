package ai.mindconnect.agent.memory.domain;

/**
 * Full history — every message ever stored is loaded and sent. No trimming, no summarization,
 * no tool-result compression.
 * <p>
 * Suitable only for very large context windows; the strategy logs a warning when the
 * payload exceeds the LLM's context size.
 */
public record FullHistoryMemoryConfig(int maxHistoryFetch) implements MemoryConfig {

    public static final int DEFAULT_MAX_HISTORY_FETCH = 500;
    public static final FullHistoryMemoryConfig DEFAULT =
            new FullHistoryMemoryConfig(DEFAULT_MAX_HISTORY_FETCH);

    public FullHistoryMemoryConfig {
        if (maxHistoryFetch < 1)
            throw new IllegalArgumentException("maxHistoryFetch must be >= 1: " + maxHistoryFetch);
    }

    @Override
    public String kind() { return "full"; }
}
