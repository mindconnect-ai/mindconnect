package ai.mindconnect.agent.tools.todo;

import ai.mindconnect.agent.tool.ToolAdvisor;

/**
 * Appends a soft progress hint to every successful tool result when the
 * session still has open todos. The agent's next planning step sees
 * something like:
 *
 * <pre>
 *   [original tool result]
 *
 *   _Open todos: 2 · Next: Find Sepultura tour dates_
 * </pre>
 *
 * <p>The phrasing is intentionally informational, not imperative: it
 * reminds the LLM where it is in the plan without nagging it to do
 * anything. Strong models ignore it when the next step is obvious;
 * weaker models pick up the hint and keep moving.
 *
 * <p>Skipped on failed tool calls (no point reminding while something
 * already broke) and on the {@code todo_write} / {@code todo_read} tools
 * themselves (their own output already shows the list).
 */
public class TodoContinuationAdvisor implements ToolAdvisor {

    private final TodoListService service;

    public TodoContinuationAdvisor(TodoListService service) {
        this.service = service;
    }

    @Override
    public int order() {
        // Late in the chain — runs after any sanitizer / redactor so the
        // hint isn't accidentally stripped, but doesn't post-process the
        // hint itself.
        return 100;
    }

    @Override
    public boolean applies(Invocation inv) {
        if (inv.sessionId() == null) return false;
        // todo_write / todo_read already print the list; appending a hint
        // would be visual noise + LLM-confusing duplication.
        String name = inv.toolName();
        return !"todo_write".equals(name) && !"todo_read".equals(name);
    }

    @Override
    public Result around(Invocation inv, Chain chain) throws Exception {
        Result result = chain.proceed(inv);
        if (result.failed()) return result;

        TodoList list = service.load(inv.sessionId());
        if (list == null || !list.hasOpenItems()) return result;

        return result.append(formatHint(list));
    }

    private static String formatHint(TodoList list) {
        long open = list.countByStatus(TodoStatus.PENDING)
                + list.countByStatus(TodoStatus.IN_PROGRESS);
        String next = nextOpenContent(list);
        StringBuilder sb = new StringBuilder("_Open todos: ").append(open);
        if (next != null) {
            sb.append(" · Next: ").append(next);
        }
        sb.append('_');
        return sb.toString();
    }

    /** Prefer the in-progress item; fall back to the first pending. */
    private static String nextOpenContent(TodoList list) {
        TodoItem inProgress = null;
        TodoItem firstPending = null;
        for (TodoItem item : list.items()) {
            if (item.status() == TodoStatus.IN_PROGRESS && inProgress == null) {
                inProgress = item;
            } else if (item.status() == TodoStatus.PENDING && firstPending == null) {
                firstPending = item;
            }
        }
        TodoItem pick = inProgress != null ? inProgress : firstPending;
        return pick == null ? null : pick.content();
    }
}
