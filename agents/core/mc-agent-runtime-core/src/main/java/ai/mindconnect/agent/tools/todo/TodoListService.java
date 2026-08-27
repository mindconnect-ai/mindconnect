package ai.mindconnect.agent.tools.todo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Use-case service over {@link TodoListRepository}: the canonical entry point
 * for tools and the admin UI to read / write a session's todo list.
 *
 * <p>Enforces the {@link TodoStatus#IN_PROGRESS}-single-item invariant on save
 * and assigns {@code order} sequentially based on insertion order so the LLM
 * does not have to manage it.
 *
 * <p>Stateless and thread-safe.
 */
public class TodoListService {

    private static final Logger log = LoggerFactory.getLogger(TodoListService.class);

    private final TodoListRepository repository;

    public TodoListService(TodoListRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the current list for {@code sessionId}, or an empty list if none
     * has been saved yet.
     */
    public TodoList load(UUID sessionId) {
        return repository.findBySession(sessionId).orElseGet(() -> TodoList.empty(sessionId));
    }

    /**
     * Overwrites the session's todo list with {@code items} in the given order.
     * Re-numbers {@code order} sequentially, validates the IN_PROGRESS-single
     * invariant, and stamps {@code updatedAt}.
     */
    public TodoList replace(UUID sessionId, List<TodoItem> items) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId is required");
        if (items == null) throw new IllegalArgumentException("items is required");

        long inProgress = items.stream().filter(i -> i.status() == TodoStatus.IN_PROGRESS).count();
        if (inProgress > 1) {
            throw new IllegalArgumentException(
                    "At most one item may be IN_PROGRESS at a time, found " + inProgress);
        }

        List<TodoItem> renumbered = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            TodoItem in = items.get(i);
            UUID id = in.id() != null ? in.id() : UUID.randomUUID();
            renumbered.add(new TodoItem(id, in.content(), in.activeForm(), in.status(), i));
        }
        TodoList saved = repository.save(new TodoList(sessionId, renumbered, Instant.now()));
        log.debug("Updated todo list for session {} ({} items, {} open)",
                sessionId, saved.items().size(),
                saved.items().size() - saved.countByStatus(TodoStatus.COMPLETED));
        return saved;
    }

    /** Drops the session's todo list. Idempotent. */
    public void clear(UUID sessionId) {
        repository.deleteBySession(sessionId);
    }
}
