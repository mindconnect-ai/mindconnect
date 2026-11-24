package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.chat.stream.StreamEvent;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What the CLI asks of an agent runtime — the one in this process or a
 * server's, behind the same calls. The CLI does not create or edit agents:
 * that happens in the admin UI or over the REST API.
 */
public interface AgentClient {

    // ── Agents ──────────────────────────────────────────────────────────────

    Optional<ManagedAgent> findAgent(AgentId agentId);

    List<ManagedAgent> listAgents();

    // ── Sessions ────────────────────────────────────────────────────────────

    ManagedSession startSession(AgentId agentId, UserId userId);

    /** @param workingDir the directory the session's tools work in; {@code null} for the runtime's default */
    ManagedSession startSession(AgentId agentId, UserId userId, String workingDir);

    ManagedSession changeWorkingDir(SessionId sessionId, String workingDir);

    /** @param additionalDirs the further directories the session may reach; {@code null} keeps the current ones */
    ManagedSession changeWorkingDir(SessionId sessionId, String workingDir, List<String> additionalDirs);

    List<ManagedSession> listSessions(AgentId agentId, UserId userId);

    List<Message> loadHistory(SessionId sessionId);

    void deleteSession(SessionId sessionId);

    /** @return how many messages with a sequence number in {@code [fromSeq, toSeq]} were deleted */
    int deleteMessages(SessionId sessionId, int fromSeq, int toSeq);

    // ── Chat ────────────────────────────────────────────────────────────────

    /** Sends the message and blocks until the turn is over; what happens meanwhile goes to the handler. */
    String chat(SessionId sessionId, String userMessage, Consumer<StreamEvent> eventHandler);

    /** @return whether there was a turn to cancel */
    boolean cancelChat(SessionId sessionId);

    /**
     * Whether a tool approval can be answered through this client while its
     * turn waits. Where it cannot, the question is answered elsewhere — in
     * the web UI of the server the client talks to.
     */
    default boolean answersApprovals() {
        return false;
    }

    /**
     * Answers the approval a tool call of the session's turn waits for; the
     * turn goes on within the {@link #chat} call that reported the question.
     *
     * @param forSession with {@code approved}: the tool may run for the rest of the session
     * @return whether the question was still open
     */
    default boolean answerApproval(SessionId sessionId, String callId, boolean approved, boolean forSession) {
        return false;
    }

    // ── Memory ──────────────────────────────────────────────────────────────

    MemoryView getWorkingMemory(SessionId sessionId);

    /** @return how many messages went into the summary */
    int compressMemory(SessionId sessionId);
}
