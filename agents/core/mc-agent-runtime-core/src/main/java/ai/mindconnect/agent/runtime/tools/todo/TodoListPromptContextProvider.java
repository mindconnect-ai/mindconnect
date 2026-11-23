package ai.mindconnect.agent.runtime.tools.todo;

import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.agent.tools.todo.TodoPromptVariable;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.PromptContextProvider;
import ai.mindconnect.agent.AuthenticationInfo;

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
        ctx.putAll(TodoPromptVariable.of(service.load(session.id())));
    }
}
