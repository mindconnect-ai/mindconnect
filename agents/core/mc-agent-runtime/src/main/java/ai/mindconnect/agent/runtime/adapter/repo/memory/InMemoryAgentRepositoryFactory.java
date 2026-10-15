package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;

/** The agent runtime's repositories in memory, one store per namespace — nothing survives the process. */
public class InMemoryAgentRepositoryFactory implements AgentRepositoryFactory {

    private final ScopeSupplier scope;
    private final int maxTracesPerConversation;

    public InMemoryAgentRepositoryFactory(ScopeSupplier scope) {
        this(scope, InMemoryLlmCallTraceRepository.DEFAULT_MAX_PER_CONVERSATION);
    }

    /** {@code maxTracesPerConversation}: the LLM call traces kept per conversation, oldest dropped; 0 keeps all. */
    public InMemoryAgentRepositoryFactory(ScopeSupplier scope, int maxTracesPerConversation) {
        this.scope = scope;
        this.maxTracesPerConversation = maxTracesPerConversation;
    }

    @Override public AgentDefinitionRepository agentDefinitionRepository() { return new InMemoryAgentDefinitionRepository(scope); }
    @Override public AgentSessionRepository agentSessionRepository() { return new InMemoryAgentSessionRepository(scope); }
    @Override public WorkingMemoryRepository workingMemoryRepository() { return new InMemoryWorkingMemoryRepository(scope); }
    @Override public ConversationSummaryRepository conversationSummaryRepository() { return new InMemoryConversationSummaryRepository(scope); }
    @Override public LlmCallTraceRepository llmCallTraceRepository() { return new InMemoryLlmCallTraceRepository(maxTracesPerConversation, scope); }
    @Override public SkillRepository skillRepository() { return new InMemorySkillRepository(scope); }
}
