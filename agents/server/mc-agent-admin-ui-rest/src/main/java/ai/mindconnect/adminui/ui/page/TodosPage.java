package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.TodoListComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.tools.todo.TodoList;
import ai.mindconnect.agent.tools.todo.TodoStatus;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiSection;

/**
 * Todo-list inspector page. Wraps a {@link TodoListComponent} and adds
 * a header summary (X / Y done, Z open) plus header actions:
 * <ul>
 *   <li>Back to the conversation.</li>
 *   <li>Clear the todo list (only when there are entries to clear).
 *       The model re-publishes a plan on the next turn that references
 *       {@code {{ todo_list_md }}}.</li>
 * </ul>
 *
 * <p>Static page: no incremental patches. After Clear the controller
 * re-renders the whole page.
 */
public final class TodosPage extends AdminPage {

    private final AgentSession session;
    private final AgentDefinition agent;
    private final TodoList list;

    private final TodoListComponent todoList;

    public TodosPage(AgentSession session, AgentDefinition agent, TodoList list) {
        this.session = session;
        this.agent = agent;
        this.list = list;
        this.todoList = new TodoListComponent(session.id(), list);
    }

    @Override
    public UiPage render() {
        String sessionId = session.id().toString();

        UiList listUi = todoList.render();

        // Back to the conversation.

        // Clear list — only when there's something to clear. The model
        // re-publishes a fresh plan on the next non-trivial turn.
        if (!list.isEmpty()) {
            UiAction clearAction = UiAction.danger("clear", "Clear list").icon("delete")
                    .confirm("Clear the entire todo list? The model will start fresh on its next turn.")
                    .dispatch("DELETE", "/admin/api/sessions/" + sessionId + "/todos");
            listUi.action(clearAction);
        }

        long open = list.items().stream()
                .filter(i -> i.status() != TodoStatus.COMPLETED).count();
        String title = "Todos — " + agent.name() + summary(open);

        var section = UiSection.of("todos-session-" + sessionId, title)
                .section("todos", "Todos", listUi);

        return UiPage.of("/admin/sessions/" + sessionId + "/todos", section);
    }

    private String summary(long open) {
        if (list.isEmpty()) return "  —  empty";
        int total = list.items().size();
        long done = list.countByStatus(TodoStatus.COMPLETED);
        return String.format("  —  %d / %d done, %d open", done, total, open);
    }
}
