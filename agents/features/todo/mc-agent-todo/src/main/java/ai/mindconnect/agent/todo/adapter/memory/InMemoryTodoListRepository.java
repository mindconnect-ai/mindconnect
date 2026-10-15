package ai.mindconnect.agent.todo.adapter.memory;

import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.todo.TodoList;
import ai.mindconnect.agent.todo.TodoListRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link TodoListRepository} — process-lifetime storage, no persistence. */
public class InMemoryTodoListRepository implements TodoListRepository {

    private final PerNamespace<Map<SessionId, TodoList>> store;

    public InMemoryTodoListRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public Optional<TodoList> findBySession(SessionId sessionId) {
        return Optional.ofNullable(store.current().get(sessionId));
    }

    @Override
    public TodoList save(TodoList list) {
        store.current().put(list.sessionId(), list);
        return list;
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        store.current().remove(sessionId);
    }
}
