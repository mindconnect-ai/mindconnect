package ai.mindconnect.agent.trace.port.out;

import ai.mindconnect.agent.trace.domain.LlmCallTrace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.trace.domain.TraceId;
import ai.mindconnect.agent.trace.domain.LlmCallTraceHeader;

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
 * turn, session — bring the tenant with them.
 */
public interface LlmCallTraceRepository {

    /** Persists one trace. Adapters are free to drop traces over a retention cap. */
    void save(LlmCallTrace trace);

    /** All traces of one turn, oldest first. */
    List<LlmCallTrace> findByTurn(String turn);

    /** All traces of the session, oldest first. */
    List<LlmCallTrace> findBySession(SessionId session);

    /**
     * The same traces as {@link #findBySession}, as headers — the list
     * without the payloads. The default serves the full traces; a store with
     * the header's columns answers from those alone.
     */
    default List<? extends LlmCallTraceHeader> findHeadersBySession(SessionId session) {
        return findBySession(session);
    }

    /**
     * Every trace whose context's {@code parentTurnId} chain leads back to
     * {@code root} — i.e. all sub-agent (and sub-sub-agent…) roundtrips
     * spawned, directly or transitively, by that top-level turn. Oldest first by {@code startedAt}. The root turn's own
     * traces are <em>not</em> included; use {@link #findByTurn} for those.
     */
    List<LlmCallTrace> findDescendants(String root);

    Optional<LlmCallTrace> findById(TraceId id);

    /** Deletes every trace belonging to the session. Idempotent. */
    void deleteBySession(SessionId session);
}
