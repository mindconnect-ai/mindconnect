package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;

/**
 * How an agent wants its history fitted into the model's context, read from
 * its metadata under {@link MemoryPlugin#MEMORY}:
 *
 * <pre>
 * memory:
 *   strategy: compact              # none | full | window | compact
 *   maxConversationRatio: 0.8      # share of the context window the history may take
 *   maxMessages: 20                # window only: at most so many messages, counted in whole turns
 *   toolResultPruning: when_full   # when_full | after_turn | off
 *   keepRecentToolResults: 3       # when_full: the newest tool results are never shortened
 *   keepRecentToolTokens: 40000    # after_turn: so many tokens of the newest tool results stay
 *   summaryPlacement: system_prompt   # system_prompt | user_message
 * </pre>
 *
 * <p>Sizes are shares of the model's context window, not tokens: the same
 * agent keeps working when it is given a model with another window.
 */
record MemoryConfig(Strategy strategy, double maxConversationRatio, int maxMessages,
                    ToolResultPruning toolResultPruning, int keepRecentToolResults, int keepRecentToolTokens,
                    SummaryPlacement summaryPlacement) {

    /** {@link #maxMessages} of an agent that sets none: the context window alone bounds the history. */
    static final int NO_LIMIT = Integer.MAX_VALUE;

    /** What an agent that says nothing gets: bounded, and without a model call of its own. */
    static final MemoryConfig DEFAULT = new MemoryConfig(Strategy.WINDOW, 0.8, NO_LIMIT,
            ToolResultPruning.WHEN_FULL, 3, 40_000, SummaryPlacement.SYSTEM_PROMPT);

    /** The settings there are; a setting with another key is one nothing reads. */
    private static final Set<String> KEYS = Set.of("strategy", "maxConversationRatio", "maxMessages",
            "toolResultPruning", "keepRecentToolResults", "keepRecentToolTokens", "summaryPlacement");

    enum Strategy {
        /** Only the turn underway: an agent that answers each question on its own. */
        NONE,
        /** Everything, as it was said. */
        FULL,
        /** The newest turns that fit; what is older is not sent. */
        WINDOW,
        /** As {@link #WINDOW}, but what is no longer sent is summarised first. */
        COMPACT
    }

    /** When an old tool result is shown as a note in place of what the tool returned. */
    enum ToolResultPruning {
        /** Only once the history no longer fits, oldest first: nothing is taken from the model before it must be. */
        WHEN_FULL,
        /**
         * As soon as a turn is two turns back, beyond a budget of the newest
         * results: a long session stays small, and the model reads a result
         * again when it needs it.
         */
        AFTER_TURN,
        /** Never: results stay as returned until their turn is left out or summarised. */
        OFF
    }

    enum SummaryPlacement {
        /** A section at the end of the instructions. */
        SYSTEM_PROMPT,
        /** A message of the user's before the rest of the history. */
        USER_MESSAGE
    }

    static MemoryConfig of(AgentDefinition agent) {
        return of(agent.metadata().get(MemoryPlugin.MEMORY));
    }

    /**
     * @throws IllegalArgumentException for a strategy, a pruning or a summary placement there is none of
     */
    static MemoryConfig of(Object setting) {
        if (!(setting instanceof Map<?, ?> memory)) {
            return DEFAULT;
        }
        return new MemoryConfig(
                memory.get("strategy") instanceof String strategy
                        ? Strategy.valueOf(strategy.toUpperCase(Locale.ROOT)) : DEFAULT.strategy(),
                memory.get("maxConversationRatio") instanceof Number ratio
                        ? ratio.doubleValue() : DEFAULT.maxConversationRatio(),
                memory.get("maxMessages") instanceof Number messages
                        ? messages.intValue() : DEFAULT.maxMessages(),
                memory.get("toolResultPruning") instanceof String pruning
                        ? ToolResultPruning.valueOf(pruning.toUpperCase(Locale.ROOT)) : DEFAULT.toolResultPruning(),
                memory.get("keepRecentToolResults") instanceof Number kept
                        ? kept.intValue() : DEFAULT.keepRecentToolResults(),
                memory.get("keepRecentToolTokens") instanceof Number tokens
                        ? tokens.intValue() : DEFAULT.keepRecentToolTokens(),
                memory.get("summaryPlacement") instanceof String placement
                        ? SummaryPlacement.valueOf(placement.toUpperCase(Locale.ROOT)) : DEFAULT.summaryPlacement());
    }

    /**
     * As {@link #of(Object)}, and refuses a key nothing reads: a setting in
     * an older form would otherwise be stored and silently do nothing.
     *
     * @throws IllegalArgumentException as {@link #of(Object)}, and for an unknown key
     */
    static MemoryConfig checked(Map<String, Object> setting) {
        for (String key : setting.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("'" + key + "' is no memory setting. There are: "
                        + String.join(", ", new TreeSet<>(KEYS)));
            }
        }
        return of(setting);
    }
}
