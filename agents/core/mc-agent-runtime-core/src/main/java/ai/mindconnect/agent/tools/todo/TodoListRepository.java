package ai.mindconnect.agent.tools.todo;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting the working todo list of an
 * {@link ai.mindconnect.agent.domain.AgentSession}.
 *
 * <p>Replace-only semantics: every {@link #save(TodoList)} overwrites the
 * previous snapshot. There is no append or patch — the LLM submits the
 * full intended state on every write, and the runtime persists it as-is.
 */
public interface TodoListRepository {

    /** Returns the current list for a session, or empty if none has been saved yet. */
    Optional<TodoList> findBySession(UUID sessionId);

    /** Overwrites the existing list. Returns the saved snapshot. */
    TodoList save(TodoList list);

    /** Drops the list for a session. Idempotent. */
    void deleteBySession(UUID sessionId);
}
