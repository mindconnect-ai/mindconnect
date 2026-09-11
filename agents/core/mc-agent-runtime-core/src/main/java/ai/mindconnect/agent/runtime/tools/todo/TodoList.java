package ai.mindconnect.agent.runtime.tools.todo;

import ai.mindconnect.agent.SessionId;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/** The session's todo list: what the agent has planned, in order, with a status each. */
public record TodoList(
        SessionId sessionId,
        List<TodoItem> items,
        Instant updatedAt
) {

    public TodoList {
        if (sessionId == null) throw new IllegalArgumentException("sessionId is required");
        if (items == null) throw new IllegalArgumentException("items is required (use empty list for clear)");
        if (updatedAt == null) updatedAt = Instant.now();
        items = List.copyOf(items);
    }

    public static TodoList empty(SessionId sessionId) {
        return new TodoList(sessionId, List.of(), Instant.now());
    }

    @JsonIgnore
    public boolean isEmpty() { return items.isEmpty(); }

    @JsonIgnore
    public boolean hasOpenItems() {
        return items.stream().anyMatch(i -> i.status() != TodoStatus.COMPLETED);
    }

    public long countByStatus(TodoStatus status) {
        return items.stream().filter(i -> i.status() == status).count();
    }

}
