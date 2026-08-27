package ai.mindconnect.agent.port.in;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Stateful handle to one chat session.
 *
 * <p>Returned by {@link AgentRuntime#openChat(UUID)} and
 * {@link AgentRuntime#attachChat(UUID)}. The session id, agent definition and
 * auth context are bound at construction — call sites do not pass them
 * around.
 *
 * <p>Implementations are expected to be cheap value-like handles: they may
 * be created and discarded freely. Multiple handles to the same session are
 * allowed; the underlying service guarantees only one concurrent {@link #send}
 * per session.
 */
public interface AgentChatClient {

    UUID sessionId();

    AgentDefinition definition();

    // ── Chat ────────────────────────────────────────────────────────────

    /**
     * Sends a user message and returns a handle to the (asynchronously
     * running) chat turn. The turn starts executing immediately; the caller
     * awaits the result via the returned handle's {@link ChatTurnHandle#result()}.
     *
     * <p>The {@code events} consumer receives granular {@link StreamEvent}s
     * (tokens, tool calls, reviewer decisions, sub-agent activity) in
     * submission order on the runtime's executor thread. Passing the listener
     * here — rather than registering it afterwards — guarantees the listener
     * is wired up before any event is emitted. Pass {@code e -> {}} if no
     * streaming is needed.
     */
    ChatTurnHandle send(String userMessage, Consumer<StreamEvent> events);

    // ── History ─────────────────────────────────────────────────────────

    /** Loads the conversation history for this session. */
    List<Message> history();

    /**
     * Permanently deletes all messages in this session's conversation whose
     * {@code sequenceNum} is in {@code [fromSeq, toSeq]} inclusive.
     *
     * @return number of messages deleted
     */
    int deleteMessages(int fromSeq, int toSeq);

    // ── Working memory ──────────────────────────────────────────────────

    /**
     * Returns a fresh snapshot of the session's working memory (token counts,
     * summary, window contents). Always built live; the persisted snapshot
     * may be stale after retroactive tool-result compression.
     */
    WorkingMemory memorySnapshot();

    /**
     * Summarizes all unsummarized messages into one or more conversation
     * summaries. Summarized messages are excluded from future LLM context
     * windows; raw messages are preserved for reporting.
     *
     * @return number of messages that were compressed
     */
    int compressMemory();
}
