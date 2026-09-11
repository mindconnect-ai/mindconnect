package ai.mindconnect.agent.runtime.tools.todo;

import ai.mindconnect.agent.SessionId;
import java.util.Optional;

/**
 * Outbound port for persisting the working todo list of an
 * {@link ai.mindconnect.agent.runtime.domain.AgentSession}.
 *
 * <p>Replace-only semantics: every {@link #save(TodoList)} overwrites the
 * previous snapshot. There is no append or patch — the LLM submits the
 * full intended state on every write, and the runtime persists it as-is.
 */
public interface TodoListRepository {

    /** Returns the current list for a session, or empty if none has been saved yet. */
    Optional<TodoList> findBySession(SessionId sessionId);

    /** Overwrites the existing list. Returns the saved snapshot. */
    TodoList save(TodoList list);

    /** Drops the list for a session. Idempotent. */
    void deleteBySession(SessionId sessionId);
}
