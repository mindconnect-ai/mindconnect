package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and retrieving LLM call traces — verbatim
 * provider wire bodies captured per chat turn for debugging.
 *
 * <p>Conceptually a write-mostly log: the agent runtime appends one trace
 * per provider roundtrip, the admin UI reads them back. Adapters may
 * enforce a per-session retention policy (drop oldest beyond N) so the
 * directory doesn't grow unboundedly on busy sessions.
 *
 * <p>A trace has its own {@link TraceId}; the things it is looked up by —
 * turn, session, conversation — bring the tenant with them.
 */
public interface LlmCallTraceRepository {

    /** Persists one trace. Adapters are free to drop traces over a retention cap. */
    void save(LlmCallTrace trace);

    /** All traces of one turn, oldest first. */
    List<LlmCallTrace> findByTurn(ChatTurnId turn);

    /** All traces of the session, oldest first. */
    List<LlmCallTrace> findBySession(SessionId session);

    /**
     * All traces stored under the conversation, oldest first. Cheaper than
     * {@link #findBySession} because it skips the cross-conversation scan —
     * call this when the conversation id is at hand (e.g. from
     * {@code AgentSession.conversationId()}).
     */
    List<LlmCallTrace> findByConversation(ConversationId conversation);

    /**
     * The same traces as {@link #findByConversation}, as headers — the
     * list without the payloads. The default serves the full traces; a
     * store with the header's columns answers from those alone.
     */
    default List<? extends LlmCallTraceHeader> findHeadersByConversation(ConversationId conversation) {
        return findByConversation(conversation);
    }

    /**
     * Every trace whose context's {@code parentTurnId} chain leads back to
     * {@code root} — i.e. all sub-agent (and sub-sub-agent…) roundtrips
     * spawned, directly or transitively, by that top-level turn. Oldest first by {@code startedAt}. The root turn's own
     * traces are <em>not</em> included; use {@link #findByTurn} for those.
     */
    List<LlmCallTrace> findDescendants(ChatTurnId root);

    Optional<LlmCallTrace> findById(TraceId id);

    /** Deletes every trace belonging to the session. Idempotent. */
    void deleteBySession(SessionId session);
}
