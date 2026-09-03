package ai.mindconnect.agent.port.out;

import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.agent.domain.view.LlmCallTraceHeader;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting and retrieving LLM call traces — verbatim
 * provider wire bodies captured per chat turn for debugging.
 *
 * <p>Conceptually a write-mostly log: the agent runtime appends one trace
 * per provider roundtrip, the admin UI reads them back. Adapters may
 * enforce a per-session retention policy (drop oldest beyond N) so the
 * directory doesn't grow unboundedly on busy sessions.
 */
public interface LlmCallTraceRepository {

    /** Persists one trace. Adapters are free to drop traces over a retention cap. */
    void save(LlmCallTrace trace);

    /** Returns all traces of a given turn, oldest first. */
    List<LlmCallTrace> findByTurn(UUID turnId);

    /** Returns all traces of a given session, oldest first. */
    List<LlmCallTrace> findBySession(UUID sessionId);

    /**
     * Returns all traces stored under a known conversation directory,
     * oldest first. Cheaper than {@link #findBySession(UUID)} because it
     * skips the cross-conversation scan — call this when you already know
     * the conversation id (e.g. from {@code AgentSession.conversationId()}).
     */
    List<LlmCallTrace> findByConversation(UUID conversationId);

    /**
     * The same traces as {@link #findByConversation}, as headers — the
     * list without the payloads. The default serves the full traces; a
     * store with the header's columns answers from those alone.
     */
    default List<? extends LlmCallTraceHeader> findHeadersByConversation(UUID conversationId) {
        return findByConversation(conversationId);
    }

    /**
     * Returns every trace whose context's {@code parentTurnId} chain leads
     * back to {@code rootTurnId} — i.e. all sub-agent (and sub-sub-agent…)
     * call roundtrips that were spawned, directly or transitively, by the
     * given top-level turn. Oldest first by {@code startedAt}.
     *
     * <p>The root turn's own traces are <em>not</em> included; use
     * {@link #findByTurn(UUID)} for those, or combine both lists in the
     * caller for a full call-tree.
     */
    List<LlmCallTrace> findDescendants(UUID rootTurnId);

    /** Returns one trace by id, if it still exists. */
    Optional<LlmCallTrace> findById(UUID id);

    /** Deletes every trace belonging to the given session. Idempotent. */
    void deleteBySession(UUID sessionId);
}
