package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.Locale;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;

/**
 * How an agent wants its history fitted into the model's context, read from
 * its metadata under {@link MemoryPlugin#MEMORY}:
 *
 * <pre>
 * memory:
 *   strategy: compact              # full | window | compact
 *   maxConversationRatio: 0.8      # share of the context window the history may take
 *   keepRecentToolResults: 3       # the newest tool results are never shortened
 *   summaryPlacement: system_prompt   # system_prompt | user_message
 * </pre>
 *
 * <p>Sizes are shares of the model's context window, not tokens: the same
 * agent keeps working when it is given a model with another window.
 */
record MemoryConfig(Strategy strategy, double maxConversationRatio, int keepRecentToolResults,
                    SummaryPlacement summaryPlacement) {

    /** What an agent that says nothing gets: bounded, and without a model call of its own. */
    static final MemoryConfig DEFAULT = new MemoryConfig(Strategy.WINDOW, 0.8, 3, SummaryPlacement.SYSTEM_PROMPT);

    enum Strategy {
        /** Everything, as it was said. */
        FULL,
        /** The newest turns that fit; what is older is not sent. */
        WINDOW,
        /** As {@link #WINDOW}, but what is no longer sent is summarised first. */
        COMPACT
    }

    enum SummaryPlacement {
        /** A section at the end of the instructions. */
        SYSTEM_PROMPT,
        /** A message of the user's before the rest of the history. */
        USER_MESSAGE
    }

    static MemoryConfig of(AgentDefinition agent) {
        if (!(agent.metadata().get(MemoryPlugin.MEMORY) instanceof Map<?, ?> memory)) {
            return DEFAULT;
        }
        return new MemoryConfig(
                memory.get("strategy") instanceof String strategy
                        ? Strategy.valueOf(strategy.toUpperCase(Locale.ROOT)) : DEFAULT.strategy(),
                memory.get("maxConversationRatio") instanceof Number ratio
                        ? ratio.doubleValue() : DEFAULT.maxConversationRatio(),
                memory.get("keepRecentToolResults") instanceof Number kept
                        ? kept.intValue() : DEFAULT.keepRecentToolResults(),
                memory.get("summaryPlacement") instanceof String placement
                        ? SummaryPlacement.valueOf(placement.toUpperCase(Locale.ROOT)) : DEFAULT.summaryPlacement());
    }
}
