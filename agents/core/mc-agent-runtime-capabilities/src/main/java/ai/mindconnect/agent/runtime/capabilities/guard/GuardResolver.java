package ai.mindconnect.agent.runtime.capabilities.guard;

import java.util.List;

import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * Where guards come from: a list in code, a configuration in the working
 * directory, a tenant's settings in a store. Contributed; the guards of all
 * resolvers apply, in the order the resolvers were installed. Asked per
 * session, because which guards hold depends on whose session it is — a
 * guard a resolver returns for a session is active in it.
 */
public interface GuardResolver {

    default List<InputGuard> inputGuards(Session session) {
        return List.of();
    }

    default List<OutputGuard> outputGuards(Session session) {
        return List.of();
    }
}
