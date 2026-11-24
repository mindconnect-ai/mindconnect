package ai.mindconnect.agent.tools.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.ToolInvocation;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.agent.tools.todo.adapter.InMemoryTodoListRepository;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import org.junit.jupiter.api.Test;

class TodoPluginTest {

    private static final AgentDefinition AGENT = new AgentDefinition("planner", "Plans.", "You plan.",
            ModelSettings.NONE, List.of(), 5, Map.of());
    private static final Session SESSION = new Session(SessionId.of("11111111-1111-1111-1111-111111111111"), null,
            null, null, Map.of(), Instant.EPOCH);

    private final PluginRegistry registry = stored(new DefaultPluginRegistry()).install(new TodoPlugin());
    private final TodoListService service = registry.get(TodoListService.class);

    private static PluginRegistry stored(PluginRegistry registry) {
        TodoListRepository lists = new InMemoryTodoListRepository(ScopeSupplier.local());
        registry.provide(TodoListRepository.class, () -> lists);
        return registry;
    }

    @Test
    void a_template_hides_the_list_until_the_agent_wrote_one() {
        assertThat(variables()).containsEntry("todo_list_md", null).containsEntry("todos", List.of());

        open("Sort the shelf");

        assertThat((String) variables().get("todo_list_md")).startsWith("\n\n## Current Todo List\n")
                .contains("Sort the shelf");
    }

    @Test
    void a_tool_result_ends_with_where_the_agent_is_in_its_plan() {
        open("Sort the shelf");

        assertThat(after("bash", ToolOutput.ok("done")))
                .isEqualTo(ToolOutput.ok("done\n\n_Open todos: 1 · Next: Sort the shelf_"));
    }

    @Test
    void no_hint_after_a_failure_after_the_todo_tools_or_without_open_items() {
        assertThat(after("bash", ToolOutput.ok("done"))).isEqualTo(ToolOutput.ok("done"));

        open("Sort the shelf");

        assertThat(after("bash", ToolOutput.failed("broke"))).isEqualTo(ToolOutput.failed("broke"));
        assertThat(after("todo_write", ToolOutput.ok("saved"))).isEqualTo(ToolOutput.ok("saved"));
    }

    private void open(String content) {
        service.replace(SESSION.id(),
                List.of(new TodoItem(UUID.randomUUID(), content, content, TodoStatus.PENDING, 0)));
    }

    private Map<String, Object> variables() {
        return registry.all(PromptVariableProvider.class).get(0).variables(AGENT, SESSION);
    }

    private ToolOutput after(String tool, ToolOutput output) {
        TurnId turn = TurnId.of("t1");
        ToolInvocation invocation = new ToolInvocation(new AgentContext(SESSION, AGENT, turn),
                new ToolCall("c1", tool, Map.of(), turn, SESSION.id()), Optional.empty());
        return registry.run(RuntimeHook.TOOL_CALL, invocation, ignored -> output);
    }
}
