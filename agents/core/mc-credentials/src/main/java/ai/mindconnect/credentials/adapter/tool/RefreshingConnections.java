package ai.mindconnect.credentials.adapter.tool;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.oauth.OAuthConnections;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Renews a token that is about to run out, on the way to the tool.
 *
 * <p>Here rather than inside the tools, and rather than on a timer: this is
 * the one place every call passes through, and a token is worth renewing
 * exactly when somebody is about to use it. A connection that is not OAuth
 * goes through untouched.
 */
public class RefreshingConnections implements Connections {

    private final Connections delegate;
    private final OAuthConnections oauth;

    public RefreshingConnections(Connections delegate, OAuthConnections oauth) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.oauth = Objects.requireNonNull(oauth, "oauth");
    }

    @Override
    public List<ToolConnection> of(UserId userId, String provider) {
        // Listing is for building a schema — nothing is used yet, so nothing is renewed.
        return delegate.of(userId, provider);
    }

    @Override
    public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
        return delegate.resolve(userId, provider, key).map(this::fresh);
    }

    private ToolConnection fresh(ToolConnection connection) {
        return connection instanceof Connection stored ? oauth.ensureFresh(stored) : connection;
    }
}
