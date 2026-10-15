package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.tools.todo.TodoList;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link TodoListRepository} on Postgres: one row of {@code mc_todo_list} per
 * session, keyed by {@code (namespace, session_id)} — a list has no id of its
 * own. Every statement matches the namespace the scope names for the current call.
 */
public final class PgTodoListRepository implements TodoListRepository {

    private final DocumentTable<TodoList> todos;
    private final ScopeSupplier scope;

    public PgTodoListRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    public PgTodoListRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.todos = DocumentTable.of(TodoList.class)
                .table("mc_todo_list")
                .partitionKey("namespace", "TEXT", l -> scope.namespace().value())
                .id("session_id", "TEXT", l -> l.sessionId().value())
                .build(sql);
    }

    public PgTodoListRepository initSchema() {
        todos.createSchema();
        return this;
    }

    @Override
    public Optional<TodoList> findBySession(SessionId sessionId) {
        return todos.findById(scope.namespace().value(), sessionId.value());
    }

    @Override
    public TodoList save(TodoList list) {
        return todos.save(list);
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        todos.deleteById(scope.namespace().value(), sessionId.value());
    }
}
