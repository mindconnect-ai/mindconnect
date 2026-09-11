package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.runtime.tools.todo.TodoList;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link TodoListRepository} — process-lifetime storage, no persistence. */
public class InMemoryTodoListRepository implements TodoListRepository {

    private final Map<SessionId, TodoList> store = new ConcurrentHashMap<>();

    @Override
    public Optional<TodoList> findBySession(SessionId sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public TodoList save(TodoList list) {
        store.put(list.sessionId(), list);
        return list;
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        store.remove(sessionId);
    }
}
