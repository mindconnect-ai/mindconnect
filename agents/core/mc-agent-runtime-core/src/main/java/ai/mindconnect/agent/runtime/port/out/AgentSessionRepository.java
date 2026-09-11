package ai.mindconnect.agent.runtime.port.out;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.agent.UserId;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Storage of chat sessions, per tenant. One session is addressed by its
 * {@link SessionId}; the sessions of an agent by the {@link AgentId}, which
 * already names the tenant; the sessions of a user by tenant and user id,
 * because a user spans tenants and the same person has a separate history in
 * each.
 *
 * <p>A session is written from many threads at once — tool tasks activate
 * tools, the approval answer adds a standing approval, an upload attaches a
 * file, the title generator names the chat. So there is no {@code save} of a
 * whole session: {@link #create} stores a new one, and {@link #update} changes
 * a stored one in a single step, on the state it finds.
 */
public interface AgentSessionRepository {

    /**
     * Stores a session that is not stored yet.
     *
     * @throws IllegalStateException when a session with this id exists already
     */
    AgentSession create(AgentSession session);

    /**
     * Applies {@code change} to the stored session and stores the result, as one
     * step: two updates of the same session never overwrite each other — the
     * second is applied to what the first one wrote. A copy read earlier is never
     * written back, because whatever was written to the session since would be lost.
     *
     * <p>{@code change} runs while the store holds the session and may run more
     * than once: build the changed session from the one given, nothing else — no
     * model call, no tool, no other store. Returning the given instance writes nothing.
     *
     * @return the session as stored afterwards, or empty when there is none
     */
    Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change);

    Optional<AgentSession> findById(SessionId id);

    /** The sessions one user has with one agent, newest first. */
    List<AgentSession> findByAgent(AgentId agent, UserId user);

    /**
     * Every top-level session of one user in one tenant, newest first — the
     * chat's session list. Sub-agent sessions ({@code parentSessionId != null})
     * are left out: they belong to the turn that spawned them, not to the
     * user's history.
     */
    List<AgentSession> findByUser(UserId user);

    /**
     * The same sessions as {@link #findByUser}, as headers. A store that
     * keeps the header's fields beside the document answers this without
     * reading a single document; the default simply serves the full
     * sessions, which are headers too.
     */
    default List<? extends AgentSessionHeader> findHeadersByUser(UserId user) {
        return findByUser(user);
    }

    /**
     * Every session spawned directly by {@code parent} through
     * {@code run_agent}. Empty for a session that never delegated.
     */
    List<AgentSession> findByParentSession(SessionId parent);

    /** Deletes the session and all its contents. No-op if not found. */
    void deleteById(SessionId id);
}
