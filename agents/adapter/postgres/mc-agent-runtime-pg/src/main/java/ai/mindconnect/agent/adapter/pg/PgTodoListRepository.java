package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.tools.todo.TodoList;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link TodoListRepository} on Postgres: one row of {@code mc_todo_list} per
 * session — the session id is the key, a list has no id of its own.
 */
public final class PgTodoListRepository implements TodoListRepository {

    private final DocumentTable<TodoList> todos;

    public PgTodoListRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgTodoListRepository(Sql sql) {
        this.todos = DocumentTable.of(TodoList.class)
                .table("mc_todo_list")
                .id("session_id", "UUID", TodoList::sessionId)
                .build(sql);
    }

    public PgTodoListRepository initSchema() {
        todos.createSchema();
        return this;
    }

    @Override
    public Optional<TodoList> findBySession(UUID sessionId) {
        return todos.findById(sessionId);
    }

    @Override
    public TodoList save(TodoList list) {
        return todos.save(list);
    }

    @Override
    public void deleteBySession(UUID sessionId) {
        todos.deleteById(sessionId);
    }
}
