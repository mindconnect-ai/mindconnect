package ai.mindconnect.agent.service.round;

import ai.mindconnect.llm.domain.ToolDefinition;

import java.util.List;
import java.util.UUID;

/**
 * Which tools this session is offered. Resolved per round, not per turn — a
 * tool_search call mid-turn activates tools the next round must already offer.
 */
public interface ToolDefinitionProvider {

    List<ToolDefinition> toolDefinitions(UUID sessionId);
}
