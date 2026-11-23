package ai.mindconnect.agent.tools.todo;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * A session's todo list in the runtime: among the values its agent's
 * instructions may refer to ({@link TodoPromptVariable}), and as a hint
 * after every tool result while items are open — informational, so a
 * strong model ignores it and a weaker one keeps moving.
 */
public final class TodoPlugin implements Plugin {

    private final TodoListService service;

    public TodoPlugin(TodoListService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "todo";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(PromptVariableProvider.class, (agent, session) ->
                TodoPromptVariable.of(service.load(SessionId.of(session.id().value()))));
        registry.after(RuntimeHook.TOOL_CALL, (invocation, output) -> {
            String tool = invocation.call().toolName();
            // Not after a failure, and not after the todo tools: their own result already shows the list.
            if (!(output instanceof ToolOutput.Completed done) || done.failed()
                    || TodoWriteTool.NAME.equals(tool) || TodoReadTool.NAME.equals(tool)) {
                return output;
            }
            TodoList list = service.load(SessionId.of(invocation.context().session().id().value()));
            return list.hasOpenItems()
                    ? ToolOutput.ok(done.text() + "\n\n" + TodoContinuationAdvisor.hint(list)) : output;
        });
    }
}
