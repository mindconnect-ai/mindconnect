package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link WorkspaceStore} — process-lifetime storage, no persistence. Files are keyed by
 * scope identity plus filename; the {@code readBytes}/{@code sizeOf}/{@code readOrNull} defaults on
 * the port are inherited.
 */
public class InMemoryWorkspaceStore implements WorkspaceStore {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    private static String scopeKey(WorkspaceScope scope) {
        return scope.type() + "|" + scope.agentId() + "|" + scope.userId() + "|" + scope.sessionId();
    }

    private static String key(WorkspaceScope scope, String filename) {
        return scopeKey(scope) + "|" + filename;
    }

    @Override
    public void write(WorkspaceScope scope, String filename, String content) {
        store.put(key(scope, filename), content);
    }

    @Override
    public Optional<String> read(WorkspaceScope scope, String filename) {
        return Optional.ofNullable(store.get(key(scope, filename)));
    }

    @Override
    public void delete(WorkspaceScope scope, String filename) {
        store.remove(key(scope, filename));
    }

    @Override
    public boolean exists(WorkspaceScope scope, String filename) {
        return store.containsKey(key(scope, filename));
    }

    @Override
    public List<String> list(WorkspaceScope scope) {
        String prefix = scopeKey(scope) + "|";
        return store.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .toList();
    }
}
