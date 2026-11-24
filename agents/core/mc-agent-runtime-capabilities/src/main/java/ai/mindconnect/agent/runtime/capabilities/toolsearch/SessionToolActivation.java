package ai.mindconnect.agent.runtime.capabilities.toolsearch;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * The tools a session has found, kept in the session's own metadata: they
 * survive a restart wherever the session does, without a store of their own.
 */
public final class SessionToolActivation implements ToolActivation {

    /** The key in a session's metadata: the names of the held-back tools the session has found. */
    public static final String ACTIVATED_TOOLS = "activatedTools";

    private final Supplier<SessionStore> sessions;

    public SessionToolActivation(Supplier<SessionStore> sessions) {
        this.sessions = sessions;
    }

    @Override
    public Set<String> activated(SessionId sessionId) {
        return sessions.get().find(sessionId)
                .map(session -> session.metadata().get(ACTIVATED_TOOLS))
                .map(SessionToolActivation::names)
                .orElse(Set.of());
    }

    @Override
    public void activate(SessionId sessionId, Collection<String> toolNames) {
        sessions.get().update(sessionId, session -> {
            Set<String> activated = new LinkedHashSet<>(names(session.metadata().get(ACTIVATED_TOOLS)));
            activated.addAll(toolNames);
            return session.withMetadata(ACTIVATED_TOOLS, List.copyOf(activated));
        });
    }

    private static Set<String> names(Object stored) {
        return stored instanceof List<?> list
                ? list.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new))
                : Set.of();
    }
}
