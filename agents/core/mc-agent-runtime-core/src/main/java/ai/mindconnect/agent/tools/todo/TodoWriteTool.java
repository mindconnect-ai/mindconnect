package ai.mindconnect.agent.tools.todo;

import ai.mindconnect.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tool that replaces the entire todo list of the calling session.
 *
 * <p>The model submits the full intended list on every call — the runtime
 * never merges partial patches. This mirrors how Claude Code's TodoWrite
 * works and keeps the contract simple.
 *
 * <p>The tool result echoes the saved list back as a compact checklist so
 * the model gets immediate confirmation in the conversation history.
 */
public class TodoWriteTool implements Tool {

    public static final String NAME = "todo_write";

    private final TodoListService service;
    private final UUID sessionId;

    public TodoWriteTool(TodoListService service, UUID sessionId) {
        this.service = service;
        this.sessionId = sessionId;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return """
                Replace the working todo list for the current session.

                Use this for planning multi-step tasks: list every step you plan to take,
                set ONE item to in_progress as you work, and update statuses as you go.
                Each call REPLACES the full list — always submit the complete state.

                When to use:
                  - The task has 3+ distinct steps.
                  - The user gave a multi-part request.
                  - You want to communicate a plan before acting.

                When NOT to use:
                  - Trivial single-step requests.
                  - Pure information queries with no actions.

                Rules:
                  - At most ONE item may be 'in_progress' at any time.
                  - Mark items 'completed' immediately once done — don't batch updates.
                  - Use the imperative 'content' form ("Implement X") and the
                    present-participle 'activeForm' ("Implementing X") for nicer UI.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "todos", Map.of(
                                "type", "array",
                                "description", "Full replacement list, in display order.",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "content", Map.of(
                                                        "type", "string",
                                                        "description", "Imperative form, e.g. 'Implement X'"
                                                ),
                                                "activeForm", Map.of(
                                                        "type", "string",
                                                        "description", "Present-participle form shown when in_progress, e.g. 'Implementing X'"
                                                ),
                                                "status", Map.of(
                                                        "type", "string",
                                                        "enum", List.of("pending", "in_progress", "completed"),
                                                        "description", "Lifecycle state. At most one item may be in_progress."
                                                )
                                        ),
                                        "required", List.of("content", "status")
                                )
                        )
                ),
                "required", List.of("todos")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> arguments) {
        Object rawTodos = arguments.get("todos");
        if (!(rawTodos instanceof List<?> rawList)) {
            throw new IllegalArgumentException("'todos' must be an array");
        }

        List<TodoItem> items = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            if (!(o instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("each todo must be an object");
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            String content = asString(m.get("content"));
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("each todo needs a non-empty 'content'");
            }
            String activeForm = asString(m.get("activeForm"));
            TodoStatus status = parseStatus(asString(m.get("status")));
            items.add(new TodoItem(UUID.randomUUID(), content, activeForm, status, 0));
        }

        TodoList saved = service.replace(sessionId, items);

        long open = saved.countByStatus(TodoStatus.PENDING)
                + saved.countByStatus(TodoStatus.IN_PROGRESS);
        return "Todo list updated (" + saved.items().size()
                + " items, " + open + " open):\n"
                + TodoFormatter.render(saved);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static TodoStatus parseStatus(String s) {
        if (s == null) throw new IllegalArgumentException("'status' is required");
        return switch (s.toLowerCase()) {
            case "pending"     -> TodoStatus.PENDING;
            case "in_progress" -> TodoStatus.IN_PROGRESS;
            case "completed"   -> TodoStatus.COMPLETED;
            default -> throw new IllegalArgumentException(
                    "unknown status '" + s + "' — expected pending|in_progress|completed");
        };
    }
}
