package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.service.ContextTokenBudget;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.memory.domain.WindowedMemoryConfig;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.service.MessageToLlmMessageMapper;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.in.ConversationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding-window strategy: keeps the last {@link WindowedMemoryConfig#windowSize()}
 * stored messages. No summarization, no tool-result compression.
 */
public class WindowedMemoryStrategy implements MemoryStrategy {

    private final WindowedMemoryConfig cfg;
    private final ConversationManager conversationManager;
    private final LlmConfigRepository llmConfigRepository;
    private final TokenCounters tokenCounterRegistry;
    private final MessageToLlmMessageMapper messageMapper = new MessageToLlmMessageMapper();

    public WindowedMemoryStrategy(WindowedMemoryConfig cfg,
                                  ConversationManager conversationManager,
                                  LlmConfigRepository llmConfigRepository,
                                  TokenCounters tokenCounterRegistry) {
        this.cfg = cfg;
        this.conversationManager = conversationManager;
        this.llmConfigRepository = llmConfigRepository;
        this.tokenCounterRegistry = tokenCounterRegistry;
    }

    @Override public String kind() { return cfg.kind(); }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return buildWindow(def, session, auth, conversationManager.loadHistory(
                session.conversationId(),
                new PageRequest(0, Math.max(cfg.windowSize() * 4, cfg.windowSize()))));
    }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                        AuthenticationInfo auth, List<Message> history) {
        List<Message> tail = history.size() <= cfg.windowSize() ? history
                : new ArrayList<>(history.subList(history.size() - cfg.windowSize(), history.size()));
        // MessageMapper needs a budget for per-message truncation; use a permissive default
        // (per-message limit equal to the whole window) — strategy doesn't impose a per-msg cap.
        ContextTokenBudget budget = permissiveBudget(def);
        return messageMapper.toMessages(ToolPairSanitizer.sanitize(tail), def, budget);
    }

    @Override
    public List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(AgentDefinition def, AgentSession session) {
        TokenCounter counter = resolveTokenCounter(def);
        List<Message> tail = lastMessages(session);
        List<WorkingMemory.WorkingMemoryMessage> messages = new ArrayList<>(tail.size());
        for (Message m : tail) {
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

    @Override
    public String systemPromptAddendum(AgentDefinition def, AgentSession session) { return ""; }

    @Override
    public CompressResult compress(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return CompressResult.empty();
    }

    @Override
    public TokenCounter resolveTokenCounter(AgentDefinition def) {
        LlmConfig llmConfig = llmConfigRepository.findByName(def.llmConfigName()).orElse(null);
        return llmConfig != null
                ? tokenCounterRegistry.forModel(llmConfig.model())
                : tokenCounterRegistry.fallback();
    }

    @Override
    public Integer contextWindowTokens(AgentDefinition def) {
        LlmConfig llmConfig = llmConfigRepository.findByName(def.llmConfigName()).orElse(null);
        return llmConfig != null ? llmConfig.contextWindowTokens() : null;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private List<Message> lastMessages(AgentSession session) {
        // Fetch enough to cover the window; oldest first, then trim from the front.
        List<Message> all = conversationManager.loadHistory(session.conversationId(),
                new PageRequest(0, Math.max(cfg.windowSize() * 4, cfg.windowSize())));
        if (all.size() <= cfg.windowSize()) return all;
        return new ArrayList<>(all.subList(all.size() - cfg.windowSize(), all.size()));
    }

    private ContextTokenBudget permissiveBudget(AgentDefinition def) {
        // Reuse the resolver via a synthetic SummarizingWindow config that allows the full
        // window for both ratios — Windowed strategy delegates per-msg truncation to the LLM.
        SummarizingWindowConfig synthetic = new SummarizingWindowConfig(
                1.0, 1.0, false, 1, 1.0, 1.0, false, 1.0,
                ai.mindconnect.agent.memory.domain.SummaryPlacement.SYSTEM_PROMPT,
                cfg.windowSize());
        return ContextTokenBudget.resolve(def, synthetic, llmConfigRepository, tokenCounterRegistry);
    }
}
