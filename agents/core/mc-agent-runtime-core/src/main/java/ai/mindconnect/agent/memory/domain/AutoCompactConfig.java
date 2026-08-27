package ai.mindconnect.agent.memory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * "Auto-compact" memory strategy, modelled after the Claude Code approach.
 * <p>
 * Live messages are kept verbatim. After each turn, if the live window's tokens
 * exceed {@link #compactAtRatio} of the LLM context window, the entire conversation
 * is summarised into a single {@link ConversationSummary}
 * and the live window restarts empty (apart from the injected summary).
 * <p>
 * Optionally, {@link #toolResultEviction} replaces older TOOL_RESULT messages with
 * a short pointer stub so they stop dominating the model's view. The originals
 * remain in storage — the agent can pull them back via the {@code fetch_tool_result}
 * builtin tool. This is gentler than full compaction and primarily fights
 * "topic-switch inertia": the model staying stuck on an old task because the
 * recent context is full of that task's tool output.
 * <p>
 * The summary is rendered into the next request according to {@link #summaryPlacement}:
 * either appended to the system prompt, or prepended as a synthetic user message
 * ("This session is being continued…").
 * <p>
 * The user can always trigger compaction manually via {@code /compress}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutoCompactConfig(
        /** Auto-compact fires when the live window exceeds this fraction of the context window. */
        double compactAtRatio,
        /** Where to inject the persisted summary on the next request. */
        SummaryPlacement summaryPlacement,
        /** Optional eviction of older tool results. {@code null} disables eviction. */
        ToolResultEvictionPolicy toolResultEviction
) implements MemoryConfig {

    public static final AutoCompactConfig DEFAULT = new AutoCompactConfig(
            0.80,
            SummaryPlacement.USER_MESSAGE,
            null   // eviction off by default — opt-in
    );

    public AutoCompactConfig {
        if (!(compactAtRatio > 0 && compactAtRatio <= 1.0))
            throw new IllegalArgumentException("compactAtRatio must be in (0, 1]: " + compactAtRatio);
        if (summaryPlacement == null)
            throw new IllegalArgumentException("summaryPlacement must not be null");
        // toolResultEviction may be null (= disabled)
    }

    @Override
    public String kind() { return "auto_compact"; }
}
