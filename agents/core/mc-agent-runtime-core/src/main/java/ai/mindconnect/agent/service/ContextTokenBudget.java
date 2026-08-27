package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

/**
 * Resolved, request-scoped token budget for the {@link SummarizingWindowConfig} strategy.
 * <p>
 * Computed once per chat call by combining the strategy's ratios with the actual
 * context window size from {@link LlmConfig}. Other memory strategies (windowed,
 * full, none) do not need this — they have their own simpler bookkeeping.
 */
public record ContextTokenBudget(
        /** Token counter appropriate for the agent's LLM model. */
        TokenCounter counter,
        /** Total context window size in tokens (from LlmConfig). */
        int contextWindow,
        /** Max tokens the unsummarized message window may consume. */
        int windowBudget,
        /** Max tokens a single message may consume before truncation. */
        int maxMessageTokens
) {
    public static final int DEFAULT_CONTEXT_WINDOW = 8_192;

    /**
     * Resolves the budget for a {@link SummarizingWindowConfig}. Falls back to
     * {@link #DEFAULT_CONTEXT_WINDOW} if the LLM config is missing.
     */
    public static ContextTokenBudget resolve(AgentDefinition def,
                                             SummarizingWindowConfig cfg,
                                             LlmConfigRepository llmConfigRepository,
                                             TokenCounters tokenCounterRegistry) {
        LlmConfig llmConfig = llmConfigRepository.findByName(def.llmConfigName()).orElse(null);

        TokenCounter counter = llmConfig != null
                ? tokenCounterRegistry.forModel(llmConfig.model())
                : tokenCounterRegistry.fallback();

        int contextWindow = llmConfig != null
                && llmConfig.contextWindowTokens() != null
                && llmConfig.contextWindowTokens() > 0
                ? llmConfig.contextWindowTokens()
                : DEFAULT_CONTEXT_WINDOW;

        return new ContextTokenBudget(
                counter,
                contextWindow,
                (int) (contextWindow * cfg.maxConversationRatio()),
                (int) (contextWindow * cfg.maxMessageRatio())
        );
    }
}
