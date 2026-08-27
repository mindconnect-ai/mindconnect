package ai.mindconnect.agent.tools.todo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full todo list owned by one {@link AgentSession}.
 *
 * <p>Replace-semantics: each save overwrites the previous list completely.
 * The LLM is expected to submit the new full state on every update; the
 * runtime never merges partial patches.
 *
 * @param sessionId  session this list belongs to
 * @param items      items in display order, oldest/lowest order first
 * @param updatedAt  wall-clock time of the last write
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TodoList(
        UUID sessionId,
        List<TodoItem> items,
        Instant updatedAt
) {
    public TodoList {
        if (sessionId == null) throw new IllegalArgumentException("sessionId is required");
        if (items == null) throw new IllegalArgumentException("items is required (use empty list for clear)");
        if (updatedAt == null) updatedAt = Instant.now();
        items = List.copyOf(items);
    }

    /** Empty list for a freshly opened session. */
    public static TodoList empty(UUID sessionId) {
        return new TodoList(sessionId, List.of(), Instant.now());
    }

    @JsonIgnore
    public boolean isEmpty() { return items.isEmpty(); }

    /** True iff at least one item is not {@link TodoStatus#COMPLETED}. */
    @JsonIgnore
    public boolean hasOpenItems() {
        return items.stream().anyMatch(i -> i.status() != TodoStatus.COMPLETED);
    }

    public long countByStatus(TodoStatus status) {
        return items.stream().filter(i -> i.status() == status).count();
    }
}
