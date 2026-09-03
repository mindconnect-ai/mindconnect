package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.port.out.TokenCounters;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.agent.port.out.LlmMessageMapper;
import ai.mindconnect.agent.service.MessageToLlmMessageMapper;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.in.ConversationManager;

import java.util.List;

/**
 * Stateless: no memory of the PAST. The window is the current episode only —
 * everything from the last user CHAT message on (the question plus this
 * turn's own tool calls and results), nothing before it.
 *
 * <p>The episode is not optional: since concept 16 the strategy builds the
 * WHOLE window (the runtime no longer appends the current user message
 * itself), so "no memory" without the episode would mean the model never
 * sees the question it is asked.
 */
public class NoMemoryStrategy implements MemoryStrategy {

    private final ConversationManager conversationManager;
    private final LlmConfigRepository llmConfigRepository;
    private final TokenCounters tokenCounterRegistry;
    private final LlmMessageMapper messageMapper;

    public NoMemoryStrategy(ConversationManager conversationManager,
                            LlmConfigRepository llmConfigRepository,
                            TokenCounters tokenCounterRegistry) {
        this(conversationManager, llmConfigRepository, tokenCounterRegistry, new MessageToLlmMessageMapper());
    }

    /** @param messageMapper how the selected messages read to the model — the host's {@link LlmMessageMapper} */
    public NoMemoryStrategy(ConversationManager conversationManager,
                            LlmConfigRepository llmConfigRepository,
                            TokenCounters tokenCounterRegistry,
                            LlmMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
        this.conversationManager = conversationManager;
        this.llmConfigRepository = llmConfigRepository;
        this.tokenCounterRegistry = tokenCounterRegistry;
    }

    @Override public String kind() { return "none"; }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return buildWindow(def, session, auth, conversationManager.loadHistory(
                session.conversationId(), new ai.mindconnect.common.PageRequest(0, Integer.MAX_VALUE)));
    }

    @Override
    public List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                        AuthenticationInfo auth, List<Message> history) {
        return messageMapper.toMessages(ToolPairSanitizer.sanitize(episode(history)), def,
                permissiveBudget(def));
    }

    /** No per-message truncation — an episode is short by construction. */
    private ai.mindconnect.agent.service.ContextTokenBudget permissiveBudget(AgentDefinition def) {
        SummarizingWindowConfig synthetic = new SummarizingWindowConfig(
                1.0, 1.0, false, 1, 1.0, 1.0, false, 1.0,
                ai.mindconnect.agent.memory.domain.SummaryPlacement.SYSTEM_PROMPT,
                Integer.MAX_VALUE);
        return ai.mindconnect.agent.service.ContextTokenBudget.resolve(
                def, synthetic, llmConfigRepository, tokenCounterRegistry);
    }

    @Override
    public List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(AgentDefinition def, AgentSession session) {
        return List.of();
    }

    /** Everything from the last user CHAT on — the question and this turn's tool traffic. */
    private List<Message> episode(List<Message> all) {
        for (int i = all.size() - 1; i >= 0; i--) {
            Message message = all.get(i);
            if (message.type() == ai.mindconnect.message.domain.MessageType.CHAT
                    && message.senderType() == ai.mindconnect.message.domain.ParticipantType.USER) {
                return all.subList(i, all.size());
            }
        }
        return List.of();
    }

    @Override
    public String systemPromptAddendum(AgentDefinition def, AgentSession session) {
        return "";
    }

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
}
