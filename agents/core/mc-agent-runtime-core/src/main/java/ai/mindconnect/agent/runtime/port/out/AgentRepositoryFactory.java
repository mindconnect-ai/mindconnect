package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;

/**
 * Creates the agent runtime's repositories for one persistence backend —
 * definitions, sessions and everything a session keeps, plus the stored
 * skills. One implementation per backend (file, in-memory, Postgres), so
 * that whoever assembles a runtime picks a factory once instead of switching
 * per repository.
 */
public interface AgentRepositoryFactory {

    AgentDefinitionRepository agentDefinitionRepository();

    AgentSessionRepository agentSessionRepository();

    WorkingMemoryRepository workingMemoryRepository();

    ConversationSummaryRepository conversationSummaryRepository();

    TodoListRepository todoListRepository();

    LlmCallTraceRepository llmCallTraceRepository();

    SkillRepository skillRepository();
}
