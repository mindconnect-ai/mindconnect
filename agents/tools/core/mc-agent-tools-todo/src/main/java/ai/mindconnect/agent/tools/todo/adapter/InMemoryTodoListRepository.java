package ai.mindconnect.agent.tools.todo.adapter;

import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.tools.todo.TodoList;
import ai.mindconnect.agent.tools.todo.TodoListRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link TodoListRepository} — process-lifetime storage, no persistence. */
public class InMemoryTodoListRepository implements TodoListRepository {

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<SessionId, TodoList>> store;

    public InMemoryTodoListRepository(ScopeSupplier scope) {
        this.scope = scope;
        this.store = new NamespaceMap<>(ConcurrentHashMap::new);
    }

    @Override
    public Optional<TodoList> findBySession(SessionId sessionId) {
        return Optional.ofNullable(store.of(scope.namespace()).get(sessionId));
    }

    @Override
    public TodoList save(TodoList list) {
        store.of(scope.namespace()).put(list.sessionId(), list);
        return list;
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        store.of(scope.namespace()).remove(sessionId);
    }
}
