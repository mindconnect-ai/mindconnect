package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.tool.ToolCallScope;

import java.util.Optional;

/**
 * The bearer token a call to the virtual environment server carries. The
 * server trusts JWTs from its configured issuers; which token that is depends
 * on the deployment — none while the server runs without authentication, a
 * fixed one for a single-user setup, an on-behalf token signed for the calling
 * user in a multi-user one.
 */
@FunctionalInterface
public interface TokenSource {

    Optional<String> token(ToolCallScope scope);

    static TokenSource none() {
        return scope -> Optional.empty();
    }

    static TokenSource fixed(String token) {
        return scope -> Optional.of(token);
    }
}
