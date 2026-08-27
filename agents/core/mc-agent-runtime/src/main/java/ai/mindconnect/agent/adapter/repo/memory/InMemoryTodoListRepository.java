package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.tools.todo.TodoList;
import ai.mindconnect.agent.tools.todo.TodoListRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link TodoListRepository} — process-lifetime storage, no persistence. */
public class InMemoryTodoListRepository implements TodoListRepository {

    private final Map<UUID, TodoList> store = new ConcurrentHashMap<>();

    @Override
    public Optional<TodoList> findBySession(UUID sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public TodoList save(TodoList list) {
        store.put(list.sessionId(), list);
        return list;
    }

    @Override
    public void deleteBySession(UUID sessionId) {
        store.remove(sessionId);
    }
}
