package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.jdbc.Sql;

/** The agent runtime's repositories as Postgres tables in the namespace the scope names per call; each is created with its schema. */
public class PgAgentRepositoryFactory implements AgentRepositoryFactory {

    private final Sql sql;
    private final ScopeSupplier scope;

    private final int maxTracesPerConversation;

    public PgAgentRepositoryFactory(Sql sql, ScopeSupplier scope) {
        this(sql, scope, 50);
    }

    /** {@code maxTracesPerConversation}: the LLM call traces kept per conversation, oldest dropped; 0 keeps all. */
    public PgAgentRepositoryFactory(Sql sql, ScopeSupplier scope, int maxTracesPerConversation) {
        this.sql = sql;
        this.scope = scope;
        this.maxTracesPerConversation = maxTracesPerConversation;
    }

    @Override public AgentDefinitionRepository agentDefinitionRepository() { return new PgAgentDefinitionRepository(sql, scope).initSchema(); }
    @Override public AgentSessionRepository agentSessionRepository() { return new PgAgentSessionRepository(sql, scope).initSchema(); }
    @Override public WorkingMemoryRepository workingMemoryRepository() { return new PgWorkingMemoryRepository(sql, scope).initSchema(); }
    @Override public ConversationSummaryRepository conversationSummaryRepository() { return new PgConversationSummaryRepository(sql, scope).initSchema(); }
    @Override public LlmCallTraceRepository llmCallTraceRepository() { return new PgLlmCallTraceRepository(sql, maxTracesPerConversation, scope).initSchema(); }
}
