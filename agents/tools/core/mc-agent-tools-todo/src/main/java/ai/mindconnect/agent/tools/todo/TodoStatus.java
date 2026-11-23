package ai.mindconnect.agent.tools.todo;

/**
 * Lifecycle state of a single {@link TodoItem}.
 *
 * <p>The {@link #IN_PROGRESS} state is exclusive: at most one item per
 * {@link TodoList} may carry it at any time. The {@code TodoListService}
 * enforces that invariant on save.
 */
public enum TodoStatus {
    /** Planned but not yet started. */
    PENDING,
    /** Actively being worked on right now. At most one per list. */
    IN_PROGRESS,
    /** Done — kept in the list for context until the list is reset. */
    COMPLETED
}
