package ai.mindconnect.agent.adapter.config;

import ai.mindconnect.agent.tools.todo.TodoContinuationAdvisor;
import ai.mindconnect.agent.tools.todo.TodoListPromptContextProvider;
import ai.mindconnect.agent.tools.todo.TodoReadTool;
import ai.mindconnect.agent.tools.todo.TodoWriteTool;
import ai.mindconnect.agent.port.out.PromptContextProvider;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.agent.tools.todo.TodoListService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the todo-tool module into the Spring context.
 *
 * <p>The {@link TodoWriteTool} / {@link TodoReadTool} are discovered via
 * {@link java.util.ServiceLoader} (see
 * {@code META-INF/services/ai.mindconnect.agent.tool.ToolFactory})
 * and grab {@link TodoListService} through the {@code ToolEnvironment}.
 *
 * <p>This config contributes Spring-driven extensions:
 * <ul>
 *   <li>{@link TodoListPromptContextProvider} — injects the list into the
 *       system prompt.</li>
 *   <li>{@link TodoContinuationAdvisor} — adds a soft "Next: …" hint to
 *       tool results while open todos remain.</li>
 * </ul>
 *
 * <p>Host applications enable the module with:
 * <pre>
 *   &#64;Import(TodoToolsConfig.class)
 * </pre>
 */
@Configuration
public class TodoToolsConfig {

    @Bean
    PromptContextProvider todoListPromptContextProvider(TodoListService todoListService) {
        return new TodoListPromptContextProvider(todoListService);
    }

    @Bean
    ToolAdvisor todoContinuationAdvisor(TodoListService todoListService) {
        return new TodoContinuationAdvisor(todoListService);
    }
}
