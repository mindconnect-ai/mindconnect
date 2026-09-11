package ai.mindconnect.agentrest.auth;

import ai.mindconnect.agent.UserId;

import java.util.Optional;

/**
 * Who is calling. The REST layer asks this and nothing else; the host's
 * security layer answers it — {@code mc-agent-security} reads it from the
 * authenticated principal (a browser login, a bearer JWT, a personal API
 * token). This module has no security dependency of its own.
 */
@FunctionalInterface
public interface CurrentUserResolver {

    /** The authenticated caller of the current request; empty when the request is not authenticated. */
    Optional<UserId> currentUser();
}
