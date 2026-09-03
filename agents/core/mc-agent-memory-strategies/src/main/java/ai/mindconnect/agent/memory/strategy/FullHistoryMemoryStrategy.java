package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.service.ContextTokenBudget;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.domain.FullHistoryMemoryConfig;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.port.out.LlmMessageMapper;
import ai.mindconnect.agent.service.MessageToLlmMessageMapper;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.in.ConversationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Full history: every stored message goes to the LLM. Suitable only for very large
 * context windows. Logs a warning when the payload exceeds the configured context size.
 */
public class FullHistoryMemoryStrategy implements MemoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(FullHistoryMemoryStrategy.class);

    private final FullHistoryMemoryConfig cfg;
    private final ConversationManager conversationManager;
    private final LlmConfigRepository llmConfigRepository;
    private final TokenCounters tokenCounterRegistry;
    private final LlmMessageMapper messageMapper;

    public FullHistoryMemoryStrategy(FullHistoryMemoryConfig cfg,
                                     ConversationManager conversationManager,
                                     LlmConfigRepository llmConfigRepository,
                                     TokenCounters tokenCounterRegistry) {
        this(cfg, conversationManager, llmConfigRepository, tokenCounterRegistry, new MessageToLlmMessageMapper());
    }

    /** @param messageMapper how the selected messages read to the model — the host's {@link LlmMessageMapper} */
    public FullHistoryMemoryStrategy(FullHistoryMemoryConfig cfg,
                                     ConversationManager conversationManager,
                                     LlmConfigRepository llmConfigRepository,
                                     TokenCounters tokenCounterRegistry,
                                     LlmMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        this.cfg = cfg;
        this.conversationManager = conversationManager;
        this.llmConfigRepository = llmConfigRepository;
        this.tokenCounterRegistry = tokenCounterRegistry;
    }

    @Override public String kind() { return cfg.kind(); }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return buildWindow(def, session, auth, conversationManager.loadHistory(
                session.conversationId(), new PageRequest(0, cfg.maxHistoryFetch())));
    }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                        AuthenticationInfo auth, List<Message> all) {
        ContextTokenBudget budget = permissiveBudget(def);
        TokenCounter counter = budget.counter();

        int total = all.stream().mapToInt(m ->
                counter.countText(m.compressed() && m.compressedContent() != null
                        ? m.compressedContent() : m.content())).sum();
        if (budget.contextWindow() > 0 && total > budget.contextWindow()) {
            log.warn("FullHistory strategy: {} messages ~{} tokens exceed context window {} — request may fail",
                    all.size(), total, budget.contextWindow());
        }
        return messageMapper.toMessages(ToolPairSanitizer.sanitize(all), def, budget);
    }

    @Override
    public List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(AgentDefinition def, AgentSession session) {
        TokenCounter counter = resolveTokenCounter(def);
        List<Message> all = conversationManager.loadHistory(session.conversationId(),
                new PageRequest(0, cfg.maxHistoryFetch()));
        List<WorkingMemory.WorkingMemoryMessage> messages = new ArrayList<>(all.size());
        for (Message m : all) {
            String effective = m.compressed() && m.compressedContent() != null
                    ? m.compressedContent() : m.content();
            int tokens = counter.countText(effective);
            String role = switch (m.senderType()) {
                case USER  -> "USER";
                case AGENT -> "AGENT";
                default    -> m.senderType().name();
            };
            messages.add(new WorkingMemory.WorkingMemoryMessage(
                    m.type().name(), role, m.sequenceNum(),
                    m.sentAt().toEpochMilli(), tokens,
                    m.content(), m.compressed(), m.compressedContent()));
        }
        return messages;
    }

    @Override public String systemPromptAddendum(AgentDefinition def, AgentSession session) { return ""; }

    @Override
    public CompressResult compress(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return CompressResult.empty();
    }

    @Override
    public TokenCounter resolveTokenCounter(AgentDefinition def) {
        LlmConfig llmConfig = llmConfigRepository.findResolvedByName(def.llmConfigName()).orElse(null);
        return llmConfig != null
                ? tokenCounterRegistry.forModel(llmConfig.model())
                : tokenCounterRegistry.fallback();
    }

    @Override
    public Integer contextWindowTokens(AgentDefinition def) {
        LlmConfig llmConfig = llmConfigRepository.findResolvedByName(def.llmConfigName()).orElse(null);
        return llmConfig != null ? llmConfig.contextWindowTokens() : null;
    }

    private ContextTokenBudget permissiveBudget(AgentDefinition def) {
        SummarizingWindowConfig synthetic = new SummarizingWindowConfig(
                1.0, 1.0, false, 1, 1.0, 1.0, false, 1.0,
                ai.mindconnect.agent.memory.domain.SummaryPlacement.SYSTEM_PROMPT,
                cfg.maxHistoryFetch());
        return ContextTokenBudget.resolve(def, synthetic, llmConfigRepository, tokenCounterRegistry);
    }
}
