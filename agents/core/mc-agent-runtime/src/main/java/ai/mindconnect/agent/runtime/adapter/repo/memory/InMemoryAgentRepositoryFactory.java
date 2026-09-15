package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentRepositoryFactory;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;

/** The agent runtime's repositories in memory — nothing survives the process. */
public class InMemoryAgentRepositoryFactory implements AgentRepositoryFactory {

    @Override public AgentDefinitionRepository agentDefinitionRepository() { return new InMemoryAgentDefinitionRepository(); }
    @Override public AgentSessionRepository agentSessionRepository() { return new InMemoryAgentSessionRepository(); }
    @Override public WorkingMemoryRepository workingMemoryRepository() { return new InMemoryWorkingMemoryRepository(); }
    @Override public ConversationSummaryRepository conversationSummaryRepository() { return new InMemoryConversationSummaryRepository(); }
    @Override public TodoListRepository todoListRepository() { return new InMemoryTodoListRepository(); }
    @Override public LlmCallTraceRepository llmCallTraceRepository() { return new InMemoryLlmCallTraceRepository(); }
    @Override public SkillRepository skillRepository() { return new InMemorySkillRepository(); }
}
