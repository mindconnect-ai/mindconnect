package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Unified CLI-facing API over the agent runtime, with both the local
 * (in-process) and remote (HTTP) implementations behind the same interface.
 *
 * <p>Listed methods are exactly those the CLI uses — this interface no longer
 * extends the runtime's inbound ports, so the CLI is free to evolve its own
 * shape independently. Migration / debugging operations like restoreSession
 * and recompressToolResults are intentionally absent: they were maintenance
 * helpers and are no longer part of the CLI surface.
 */
public interface AgentClient {

    // ── Agents ──────────────────────────────────────────────────────────────

    Optional<AgentDefinition> findAgent(Namespace namespace, UUID agentId);

    List<AgentDefinition> listAgents(Namespace namespace);

    // ── Sessions ────────────────────────────────────────────────────────────

    AgentSession startSession(UUID agentDefinitionId, Namespace namespace, String userId);

    List<AgentSession> listSessions(UUID agentDefinitionId, Namespace namespace, String userId);

    List<Message> loadHistory(UUID sessionId);

    void deleteSession(UUID sessionId);

    int deleteMessages(UUID sessionId, int fromSeq, int toSeq);

    // ── Chat ────────────────────────────────────────────────────────────────

    String chat(UUID sessionId, String userMessage, Consumer<StreamEvent> eventHandler);

    boolean cancelChat(UUID sessionId);

    // ── Memory ──────────────────────────────────────────────────────────────

    WorkingMemory getWorkingMemory(UUID sessionId);

    int compressMemory(UUID sessionId);
}
