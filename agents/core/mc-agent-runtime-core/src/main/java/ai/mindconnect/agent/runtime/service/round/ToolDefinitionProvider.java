package ai.mindconnect.agent.runtime.service.round;

import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.llm.domain.ToolDefinition;

import java.util.List;

/**
 * Which tools this session is offered. Resolved per round, not per turn — a
 * tool_search call mid-turn activates tools the next round must already offer.
 */
public interface ToolDefinitionProvider {

    List<ToolDefinition> toolDefinitions(SessionId sessionId);
}
