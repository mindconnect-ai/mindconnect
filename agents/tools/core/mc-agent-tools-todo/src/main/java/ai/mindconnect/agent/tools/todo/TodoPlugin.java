package ai.mindconnect.agent.tools.todo;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/** A session's todo list among the values its agent's instructions may refer to; see {@link TodoPromptVariable}. */
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
    }
}
