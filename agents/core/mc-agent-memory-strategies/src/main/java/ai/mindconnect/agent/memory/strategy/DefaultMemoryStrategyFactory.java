package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.port.out.LlmMessageMapper;
import ai.mindconnect.agent.service.MessageToLlmMessageMapper;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.memory.domain.FullHistoryMemoryConfig;
import ai.mindconnect.agent.memory.domain.MemoryConfig;
import ai.mindconnect.agent.memory.domain.NoMemoryConfig;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.memory.domain.WindowedMemoryConfig;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.port.out.ToolResultSummarizer;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.port.out.TokenCounters;
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

    public DefaultMemoryStrategyFactory(ConversationManager conversationManager,
                                        ConversationSummaryRepository summaryRepository,
                                        ToolResultSummarizer toolResultSummarizer,
                                        AgentTaskRunner agentTaskRunner,
                                        TokenCounters tokenCounterRegistry,
                                        LlmConfigRepository llmConfigRepository) {
        this(conversationManager, summaryRepository, toolResultSummarizer, agentTaskRunner,
                tokenCounterRegistry, llmConfigRepository, new MessageToLlmMessageMapper());
    }

    /** @param messageMapper how messages read to the model, handed to every strategy this factory creates */
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
