package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.runtime.port.out.LlmMessageMapper;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.runtime.memory.domain.FullHistoryMemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.MemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.NoMemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.runtime.memory.domain.WindowedMemoryConfig;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.runtime.port.out.ToolResultSummarizer;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.port.out.TokenCounters;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.port.in.ConversationManager;

/**
 * Default factory: pattern-matches on {@link MemoryConfig} subtype. The sealed
 * hierarchy makes the switch exhaustive — adding a new MemoryConfig subtype causes
 * a compile error here until it is handled.
 */
public class DefaultMemoryStrategyFactory implements MemoryStrategyFactory {

    private final ConversationManager conversationManager;
    private final ConversationSummaryRepository summaryRepository;
    private final ToolResultSummarizer toolResultSummarizer;
    private final AgentTaskRunner agentTaskRunner;
    private final TokenCounters tokenCounterRegistry;
    private final LlmConfigRepository llmConfigRepository;
    private final LlmMessageMapper messageMapper;

    /** @param messageMapper how messages read to the model — the host's {@link LlmMessageMapper}, or {@code new MessageToLlmMessageMapper()} */
    public DefaultMemoryStrategyFactory(ConversationManager conversationManager,
                                        ConversationSummaryRepository summaryRepository,
                                        ToolResultSummarizer toolResultSummarizer,
                                        AgentTaskRunner agentTaskRunner,
                                        TokenCounters tokenCounterRegistry,
                                        LlmConfigRepository llmConfigRepository,
                                        LlmMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        this.conversationManager = conversationManager;
        this.summaryRepository = summaryRepository;
        this.toolResultSummarizer = toolResultSummarizer;
        this.agentTaskRunner = agentTaskRunner;
        this.tokenCounterRegistry = tokenCounterRegistry;
        this.llmConfigRepository = llmConfigRepository;
    }

    @Override
    public MemoryStrategy create(AgentDefinition def) {
        MemoryConfig cfg = def.effectiveMemoryConfig();
        return switch (cfg) {
            case NoMemoryConfig c -> new NoMemoryStrategy(conversationManager, llmConfigRepository, tokenCounterRegistry, messageMapper);
            case WindowedMemoryConfig c ->
                    new WindowedMemoryStrategy(c, conversationManager, llmConfigRepository, tokenCounterRegistry, messageMapper);
            case SummarizingWindowConfig c ->
                    new SummarizingWindowStrategy(c, conversationManager,
                            summaryRepository, toolResultSummarizer, agentTaskRunner,
                            tokenCounterRegistry, llmConfigRepository, messageMapper);
            case AutoCompactConfig c ->
                    new AutoCompactStrategy(c, conversationManager, summaryRepository, agentTaskRunner,
                            tokenCounterRegistry, llmConfigRepository, messageMapper);
            case FullHistoryMemoryConfig c ->
                    new FullHistoryMemoryStrategy(c, conversationManager, llmConfigRepository, tokenCounterRegistry, messageMapper);
        };
    }
}
