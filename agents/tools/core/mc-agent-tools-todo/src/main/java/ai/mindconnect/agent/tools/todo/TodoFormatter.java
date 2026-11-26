package ai.mindconnect.agent.tools.todo;

/**
 * Renders a {@link TodoList} as a compact Markdown checklist for both
 * tool results and the prompt-context block.
 *
 * <p>Glyph convention:
 * <ul>
 *   <li>{@code [ ]} — pending</li>
 *   <li>{@code [~]} — in progress (current focus)</li>
 *   <li>{@code [x]} — completed</li>
 * </ul>
 *
 * The {@code [~]} glyph is non-standard but stands out visually so the
 * model can spot its active item at a glance.
 */
public final class TodoFormatter {

    private TodoFormatter() {}

    public static String render(TodoList list) {
        if (list == null || list.isEmpty()) return "(no todos)";
        StringBuilder sb = new StringBuilder();
        for (TodoItem item : list.items()) {
            sb.append(glyph(item.status())).append(' ').append(item.displayText()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String glyph(TodoStatus status) {
        return switch (status) {
            case PENDING     -> "[ ]";
            case IN_PROGRESS -> "[~]";
            case COMPLETED   -> "[x]";
        };
    }
}
