package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryRepository;
import ai.mindconnect.jdbc.Sql;

/** The agent runtime's repositories as Postgres tables, bound to one namespace; each is created with its schema. */
public class PgAgentRepositoryFactory implements AgentRepositoryFactory {

    private final Sql sql;
    private final Namespace namespace;

    private final int maxTracesPerConversation;

    public PgAgentRepositoryFactory(Sql sql, Namespace namespace) {
        this(sql, namespace, 50);
    }

    /** {@code maxTracesPerConversation}: the LLM call traces kept per conversation, oldest dropped; 0 keeps all. */
    public PgAgentRepositoryFactory(Sql sql, Namespace namespace, int maxTracesPerConversation) {
        this.sql = sql;
        this.namespace = namespace;
        this.maxTracesPerConversation = maxTracesPerConversation;
    }

    @Override public AgentDefinitionRepository agentDefinitionRepository() { return new PgAgentDefinitionRepository(sql, namespace).initSchema(); }
    @Override public AgentSessionRepository agentSessionRepository() { return new PgAgentSessionRepository(sql, namespace).initSchema(); }
    @Override public WorkingMemoryRepository workingMemoryRepository() { return new PgWorkingMemoryRepository(sql, namespace).initSchema(); }
    @Override public ConversationSummaryRepository conversationSummaryRepository() { return new PgConversationSummaryRepository(sql, namespace).initSchema(); }
    @Override public TodoListRepository todoListRepository() { return new PgTodoListRepository(sql, namespace).initSchema(); }
    @Override public LlmCallTraceRepository llmCallTraceRepository() { return new PgLlmCallTraceRepository(sql, maxTracesPerConversation, namespace).initSchema(); }
    @Override public SkillRepository skillRepository() { return new PgSkillRepository(sql, namespace).initSchema(); }
    @Override public UserMemoryRepository userMemoryRepository() { return new PgUserMemoryRepository(sql, namespace).initSchema(); }
}
