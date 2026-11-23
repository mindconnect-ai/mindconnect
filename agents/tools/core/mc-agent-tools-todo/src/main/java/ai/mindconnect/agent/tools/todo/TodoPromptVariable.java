package ai.mindconnect.agent.tools.todo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A session's todo list as values an agent's instructions may refer to:
 * {@code todo_list_md}, the list as a Markdown block with its heading, and
 * {@code todos}, its items as maps for a template that formats them itself.
 */
public final class TodoPromptVariable {

    private TodoPromptVariable() {}

    /**
     * For a list without items {@code todo_list_md} is {@code null}, so a
     * template's {@code {% if todo_list_md %}} hides the section. Once the
     * agent has written anything, the whole list is there, done items
     * included: hiding them is what made an agent forget its plan after the
     * first tick.
     */
    public static Map<String, Object> of(TodoList list) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("todo_list_md", list.isEmpty() ? null : renderMarkdown(list));
        variables.put("todos", list.isEmpty() ? List.of() : toMaps(list));
        return variables;
    }

    private static String renderMarkdown(TodoList list) {
        long open = list.countByStatus(TodoStatus.PENDING)
                + list.countByStatus(TodoStatus.IN_PROGRESS);
        long done = list.countByStatus(TodoStatus.COMPLETED);
        String trailer = open == 0
                ? "all done — call `todo_write` with a fresh plan if more work is needed"
                : "use `todo_write` to mark items completed or refine the plan";
        return "\n\n## Current Todo List\n"
                + "(" + list.items().size() + " items: "
                + open + " open · " + done + " done — " + trailer + ")\n\n"
                + TodoFormatter.render(list)
                + "\n";
    }

    private static List<Map<String, Object>> toMaps(TodoList list) {
        List<Map<String, Object>> out = new ArrayList<>(list.items().size());
        for (TodoItem item : list.items()) {
            out.add(Map.of(
                    "content",     item.content(),
                    "activeForm",  item.activeForm() == null ? "" : item.activeForm(),
                    "status",      item.status().name().toLowerCase(Locale.ROOT),
                    "displayText", item.displayText()
            ));
        }
        return out;
    }
}
