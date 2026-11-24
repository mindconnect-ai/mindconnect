package ai.mindconnect.agent.runtime.capabilities.toolsearch;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;

/** Activations that live as long as the process: enough for a library user and for tests. */
public final class InMemoryToolActivation implements ToolActivation {

    private final Map<String, Set<String>> activated = new ConcurrentHashMap<>();
    private final NamespaceProvider namespaces;

    public InMemoryToolActivation(NamespaceProvider namespaces) {
        this.namespaces = namespaces;
    }

    /** A session id means something only within its namespace. */
    private String key(SessionId sessionId) {
        return namespaces.current() + "/" + sessionId.value();
    }

    @Override
    public Set<String> activated(SessionId sessionId) {
        return Set.copyOf(activated.getOrDefault(key(sessionId), Set.of()));
    }

    @Override
    public void activate(SessionId sessionId, Collection<String> toolNames) {
        activated.computeIfAbsent(key(sessionId), id -> ConcurrentHashMap.newKeySet()).addAll(toolNames);
    }
}
