package ai.mindconnect.agent.tools.todo;

import ai.mindconnect.agent.tool.Tool;

import java.util.Map;
import java.util.UUID;

/**
 * Read-only view of the session's current todo list.
 *
 * <p>The {@code todo_write} tool is the only way to <em>mutate</em> the
 * list, but agents need a no-side-effect read path for two reasons:
 * <ul>
 *   <li>Long deep-research flows may run many turns where the system
 *       prompt re-render has already happened — the agent needs an
 *       on-demand check of "what's still open" without rewriting state
 *       (and risking accidentally dropping items).</li>
 *   <li>It splits the read-modify-write cycle cleanly: the agent can
 *       call {@code todo_read}, decide what changed, and call
 *       {@code todo_write} with the new full list.</li>
 * </ul>
 *
 * <p>Mirrors the formatting of {@link TodoFormatter} so the agent sees
 * the same shape it gets in the system prompt.
 */
public class TodoReadTool implements Tool {

    public static final String NAME = "todo_read";

    private final TodoListService service;
    private final UUID sessionId;

    public TodoReadTool(TodoListService service, UUID sessionId) {
        this.service = service;
        this.sessionId = sessionId;
    }

    @Override public String name() { return NAME; }

    @Override
    public String description() {
        return """
                Return the current session's todo list.

                Use this to check what's still open or what you've already done
                before deciding the next step. Has no side effects — it doesn't
                modify anything. If you want to mark a step done or refine the
                plan, call `todo_write` with the new full list afterwards.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        // No arguments — the session is implicit.
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        TodoList list = service.load(sessionId);
        if (list == null || list.isEmpty()) {
            return "(no todos)";
        }
        long open = list.countByStatus(TodoStatus.PENDING)
                + list.countByStatus(TodoStatus.IN_PROGRESS);
        long done = list.countByStatus(TodoStatus.COMPLETED);
        return "Todo list (" + list.items().size()
                + " items: " + open + " open · " + done + " done):\n"
                + TodoFormatter.render(list);
    }
}
