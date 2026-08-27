package ai.mindconnect.agent.memory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Window + summarization strategy.
 * <p>
 * Live recent messages are kept until they exceed {@code maxConversationRatio} of the LLM
 * context window. Older messages are summarized via the {@code conversation-summarizer}
 * stateless agent and persisted as {@link ConversationSummary}.
 * Large tool results are eagerly compressed once the window pressure crosses
 * {@code compressWhenWindowAboveRatio} — gated by {@link #compressToolResults}.
 * <p>
 * Conversation auto-summarization fires after each turn when the live window
 * exceeds {@link #autoSummarizeRatio} of the context window — gated by
 * {@link #autoSummarize}.
 * <p>
 * The location of the injected summary is controlled by {@link #summaryPlacement}.
 * <p>
 * All knobs are configurable; see {@link #DEFAULT} for the values used when an
 * AgentDefinition does not override them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SummarizingWindowConfig(
        /** Fraction of the context window the live message tail may consume. */
        double maxConversationRatio,
        /** Max fraction a single message may consume before being truncated. */
        double maxMessageRatio,
        /** Master switch for tool-result compression. When false, none of the tool-result settings apply. */
        boolean compressToolResults,
        /** Floor for the per-message tool-result compression threshold (in tokens). */
        int    minToolResultCompressTokens,
        /**
         * Per-message tool-result compression threshold expressed as a fraction of the
         * context window. Effective threshold = max(minToolResultCompressTokens,
         * contextWindow * toolResultThresholdRatio).
         */
        double toolResultThresholdRatio,
        /** Skip tool-result compression while the live window is below this fraction of budget. */
        double compressWhenWindowAboveRatio,
        /** Master switch for automatic conversation summarization after each turn. */
        boolean autoSummarize,
        /**
         * Auto-summarize fires when the live window exceeds this fraction of the
         * context window after a turn. Typically &gt;= maxConversationRatio so
         * summarization happens before window-trim drops messages.
         */
        double autoSummarizeRatio,
        /** Where to inject the persisted summaries when building the next LLM request. */
        SummaryPlacement summaryPlacement,
        /** Page size when loading conversation history. */
        int    maxHistoryFetch
) implements MemoryConfig {

    public static final SummarizingWindowConfig DEFAULT = new SummarizingWindowConfig(
            0.80,    // maxConversationRatio
            0.20,    // maxMessageRatio
            true,    // compressToolResults
            500,     // minToolResultCompressTokens
            0.02,    // toolResultThresholdRatio (= contextWindow / 50)
            0.50,    // compressWhenWindowAboveRatio
            false,   // autoSummarize  — opt-in
            0.75,    // autoSummarizeRatio
            SummaryPlacement.SYSTEM_PROMPT,
            500      // maxHistoryFetch
    );

    public SummarizingWindowConfig {
        requireRatio(maxConversationRatio,    "maxConversationRatio");
        requireRatio(maxMessageRatio,         "maxMessageRatio");
        requireRatio(toolResultThresholdRatio, "toolResultThresholdRatio");
        requireRatio(compressWhenWindowAboveRatio, "compressWhenWindowAboveRatio");
        requireRatio(autoSummarizeRatio,       "autoSummarizeRatio");
        if (minToolResultCompressTokens < 0)
            throw new IllegalArgumentException("minToolResultCompressTokens must be >= 0: "
                    + minToolResultCompressTokens);
        if (maxHistoryFetch < 1)
            throw new IllegalArgumentException("maxHistoryFetch must be >= 1: " + maxHistoryFetch);
        if (summaryPlacement == null)
            throw new IllegalArgumentException("summaryPlacement must not be null");
    }

    private static void requireRatio(double v, String name) {
        if (!(v > 0 && v <= 1.0))
            throw new IllegalArgumentException(name + " must be in (0, 1]: " + v);
    }

    @Override
    public String kind() { return "summarizing_window"; }
}
