package ai.mindconnect.agent.protocol;

/**
 * Token accounting for one response, aggregated over its LLM calls.
 * Child responses account separately; a tree total is a client-side sum.
 */
public record Usage(long inputTokens, long outputTokens) {

    public static final Usage ZERO = new Usage(0, 0);

    public long totalTokens() { return inputTokens + outputTokens; }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
