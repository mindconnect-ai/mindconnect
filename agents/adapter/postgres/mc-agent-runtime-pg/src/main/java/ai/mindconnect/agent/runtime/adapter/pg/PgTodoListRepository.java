package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
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
 * own. The repository is bound to one namespace and every statement matches it.
 */
public final class PgTodoListRepository implements TodoListRepository {

    private final DocumentTable<TodoList> todos;
    private final Namespace namespace;

    public PgTodoListRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgTodoListRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.todos = DocumentTable.of(TodoList.class)
                .table("mc_todo_list")
                .partitionKey("namespace", "TEXT", l -> namespace.value())
                .id("session_id", "TEXT", l -> l.sessionId().value())
                .build(sql);
    }

    public PgTodoListRepository initSchema() {
        todos.createSchema();
        return this;
    }

    @Override
    public Optional<TodoList> findBySession(SessionId sessionId) {
        return todos.findById(namespace.value(), sessionId.value());
    }

    @Override
    public TodoList save(TodoList list) {
        return todos.save(list);
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        todos.deleteById(namespace.value(), sessionId.value());
    }
}
