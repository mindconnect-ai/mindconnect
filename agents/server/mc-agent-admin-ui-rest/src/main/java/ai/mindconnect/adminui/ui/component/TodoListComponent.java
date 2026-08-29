package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.tools.todo.TodoItem;
import ai.mindconnect.agent.tools.todo.TodoList;
import ai.mindconnect.ui.model.UiList;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The agent's checklist for the current session — same items the LLM
 * sees in its prompt context via {@code {{ todo_list_md }}}. Each item
 * renders with its status glyph (pending / in-progress / completed)
 * and a textual status description.
 *
 * <p>List is read-only from the UI side; the agent is what mutates it.
 * The "Clear" header action lives on the containing page rather than
 * inside this component because it's an out-of-band operator override.
 */
public final class TodoListComponent implements UiComponent {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final UUID sessionId;
    private final TodoList list;

    public TodoListComponent(UUID sessionId, TodoList list) {
        this.sessionId = sessionId;
        this.list = list;
    }

    @Override
    public String id() {
        return "todo-list-" + sessionId;
    }

    @Override
    public UiList render() {
        String header = list.isEmpty()
                ? "No todos yet"
                : list.items().size() + " items"
                  + " · updated " + DT_FMT.format(list.updatedAt());
        var ui = UiList.of(id(), header);

        if (list.isEmpty()) {
            ui.item(UiList.Item.of("empty", "(no todos)")
                    .description("The agent has not published a plan for this session yet. "
                            + "Ask it to do something non-trivial and the `todo_write` tool "
                            + "should appear in the next turn."));
            return ui;
        }

        for (TodoItem item : list.items()) {
            String glyph = switch (item.status()) {
                case PENDING     -> "☐";
                case IN_PROGRESS -> "▶";
                case COMPLETED   -> "✓";
            };
            String label = glyph + "  " + item.displayText();
            String desc = switch (item.status()) {
                case PENDING     -> "pending";
                case IN_PROGRESS -> "in progress";
                case COMPLETED   -> "completed";
            };
            ui.item(UiList.Item.of("todo-" + item.id(), label).description(desc));
        }
        return ui;
    }
}
