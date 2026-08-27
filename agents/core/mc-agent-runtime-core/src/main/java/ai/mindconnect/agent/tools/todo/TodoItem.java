package ai.mindconnect.agent.tools.todo;

import java.util.UUID;

/**
 * One entry on an agent's working todo list.
 *
 * @param id          stable identifier; lets the LLM target items by id when patching
 * @param content     short imperative form, e.g. {@code "Implement TodoWriteTool"}
 * @param activeForm  present-participle phrasing used when the item is in progress,
 *                    e.g. {@code "Implementing TodoWriteTool"}. Nullable — falls back
 *                    to {@code content} in rendered output.
 * @param status      lifecycle state, see {@link TodoStatus}
 * @param order       position in the list, lower first; assigned by the service so the
 *                    LLM does not have to think about it
 */
public record TodoItem(
        UUID id,
        String content,
        String activeForm,
        TodoStatus status,
        int order
) {
    public TodoItem {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
        if (status == null) throw new IllegalArgumentException("status is required");
    }

    /** Display text — prefers {@code activeForm} when the item is in progress. */
    public String displayText() {
        return (status == TodoStatus.IN_PROGRESS && activeForm != null && !activeForm.isBlank())
                ? activeForm : content;
    }
}
