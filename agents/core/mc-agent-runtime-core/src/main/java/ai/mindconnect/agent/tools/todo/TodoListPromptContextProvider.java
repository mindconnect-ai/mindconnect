package ai.mindconnect.agent.tools.todo;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.PromptContextProvider;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Surfaces the session's current todo list into the prompt-template context.
 *
 * <p>Two variables are exposed:
 * <ul>
 *   <li>{@code todo_list_md} — fully rendered Markdown block (heading + list)
 *       suitable for direct inclusion via <code>{{ todo_list_md | raw }}</code>.
 *       {@code null} when the list is empty or fully completed, so
 *       <code>{% if todo_list_md %}</code> works intuitively.</li>
 *   <li>{@code todos} — list of plain maps ({@code content, activeForm, status})
 *       for prompt authors who want to format it themselves via
 *       <code>{% for t in todos %}</code>.</li>
 * </ul>
 *
 * <p>Both variables are skipped entirely when the list has no open items —
 * we don't want to nag the model with stale "all done" lists every turn.
 */
public class TodoListPromptContextProvider implements PromptContextProvider {

    private final TodoListService service;

    public TodoListPromptContextProvider(TodoListService service) {
        this.service = service;
    }

    /**
     * Slightly delayed so cheaper providers run first. Reading the file
     * adapter is cheap, but we don't want it to gate template rendering
     * when the prompt doesn't even reference {@code todos}.
     */
    @Override
    public int priority() { return 10; }

    @Override
    public void contribute(Map<String, Object> ctx,
                            AgentDefinition def,
                            AgentSession session,
                            AuthenticationInfo auth) {
        if (session == null) return;
        TodoList list = service.load(session.id());
        // Empty list (no items at all) → keep the variables null so the
        // template's {% if todo_list_md %} guard hides the section. As soon
        // as the agent has written anything, surface the FULL list — open
        // AND completed items — so subsequent turns have a record of what
        // was done. Hiding completed items on prior turns is what made the
        // agent "forget" its plan once it ticked the first box.
        if (list.isEmpty()) {
            ctx.put("todo_list_md", null);
            ctx.put("todos", List.of());
            return;
        }
        ctx.put("todo_list_md", renderMarkdown(list));
        ctx.put("todos", toMaps(list));
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
