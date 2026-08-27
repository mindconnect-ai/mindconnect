package ai.mindconnect.agent.service.round;

/**
 * Token usage of one model call; the turn sums its rounds with {@link #plus}.
 * Runtime-owned on purpose — the protocol module has its own Usage for the
 * wire, mapped at the API edge (concept 16).
 */
public record Usage(long inputTokens, long outputTokens) {

    public static final Usage ZERO = new Usage(0, 0);

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
