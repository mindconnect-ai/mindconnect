package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;

/**
 * Creates the agent runtime's repositories for one persistence backend —
 * definitions, sessions and everything a session keeps. One implementation per backend (file, in-memory, Postgres), so
 * that whoever assembles a runtime picks a factory once instead of switching
 * per repository.
 */
public interface AgentRepositoryFactory {

    AgentDefinitionRepository agentDefinitionRepository();

    AgentSessionRepository agentSessionRepository();

    WorkingMemoryRepository workingMemoryRepository();

    ConversationSummaryRepository conversationSummaryRepository();

    LlmCallTraceRepository llmCallTraceRepository();
}
