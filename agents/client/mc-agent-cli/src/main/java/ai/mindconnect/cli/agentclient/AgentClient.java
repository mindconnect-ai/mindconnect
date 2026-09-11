package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.agent.UserId;

import java.util.List;
import java.util.Optional;
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
 *
 * <p>Agents and sessions are addressed by their typed ids; the remote
 * implementation puts an id's value into the path, the local one hands it
 * straight to the runtime.
 */
public interface AgentClient {

    // ── Agents ──────────────────────────────────────────────────────────────

    Optional<AgentDefinition> findAgent(AgentId agentId);

    List<AgentDefinition> listAgents();

    // ── Sessions ────────────────────────────────────────────────────────────

    AgentSession startSession(AgentId agentDefinitionId, UserId userId);

    /**
     * Same, with the directory the session works in — where the CLI was
     * launched — or {@code null} for the runtime's default.
     */
    AgentSession startSession(AgentId agentDefinitionId, UserId userId, String workingDir);

    /** Moves a session to another working directory ({@code null} clears it); {@code /cd}. */
    AgentSession changeWorkingDir(SessionId sessionId, String workingDir);

    /**
     * Same, and replaces the additional directories the session may reach by
     * absolute path ({@code /add-dir}); {@code null} keeps them as they are.
     */
    AgentSession changeWorkingDir(SessionId sessionId, String workingDir, List<String> additionalDirs);

    List<AgentSession> listSessions(AgentId agentDefinitionId, UserId userId);

    List<Message> loadHistory(SessionId sessionId);

    void deleteSession(SessionId sessionId);

    int deleteMessages(SessionId sessionId, int fromSeq, int toSeq);

    // ── Chat ────────────────────────────────────────────────────────────────

    String chat(SessionId sessionId, String userMessage, Consumer<StreamEvent> eventHandler);

    boolean cancelChat(SessionId sessionId);

    // ── Memory ──────────────────────────────────────────────────────────────

    WorkingMemory getWorkingMemory(SessionId sessionId);

    int compressMemory(SessionId sessionId);
}
