package ai.mindconnect.agent.memory.port.in;

import ai.mindconnect.agent.domain.AgentDefinition;

/** Selects (or creates) the {@link MemoryStrategy} matching an agent's MemoryConfig. */
public interface MemoryStrategyFactory {
    MemoryStrategy create(AgentDefinition def);
}
