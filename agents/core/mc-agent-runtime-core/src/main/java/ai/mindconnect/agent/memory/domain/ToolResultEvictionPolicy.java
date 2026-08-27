package ai.mindconnect.agent.memory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Policy for replacing older TOOL_RESULT messages in the live window with a short
 * stub, so they no longer dominate the LLM's view of the conversation.
 * <p>
 * The originals stay in the message repository — the agent can pull them back via
 * the {@code fetch_tool_result} builtin tool, addressed by message id.
 * <p>
 * Eviction triggers when both:
 * <ul>
 *   <li>the message is older than {@link #afterTurns} user turns, and</li>
 *   <li>the original token count is at least {@link #aboveTokens}.</li>
 * </ul>
 * <p>
 * "Turn" here means a USER chat message — counting how many user inputs have
 * arrived since this tool call. {@code afterTurns = 1} therefore evicts tool
 * results that were produced before the most recent user message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResultEvictionPolicy(
        /** Evict tool results older than this many user turns. */
        int afterTurns,
        /** Only evict tool results whose original token count is at least this. */
        int aboveTokens
) {
    public static final ToolResultEvictionPolicy DEFAULT =
            new ToolResultEvictionPolicy(1, 500);

    public ToolResultEvictionPolicy {
        if (afterTurns < 1)
            throw new IllegalArgumentException("afterTurns must be >= 1: " + afterTurns);
        if (aboveTokens < 0)
            throw new IllegalArgumentException("aboveTokens must be >= 0: " + aboveTokens);
    }
}
