package ai.mindconnect.credentials.adapter.tool;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.credentials.service.ConnectionService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The tool side of {@link ConnectionService}: what the binding decorator asks
 * when it needs to know whose account a call runs on.
 *
 * <p>Thin on purpose. The rules — one default per provider, stable keys, a
 * blank secret meaning "leave it" — belong to the service, because an OAuth
 * callback has to follow them too. This only narrows the entity to the view a
 * tool is allowed to see.
 */
public class ServiceConnections implements Connections {

    private final ConnectionService connections;

    public ServiceConnections(ConnectionService connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public List<ToolConnection> of(UserId userId, String provider) {
        return List.copyOf(connections.of(userId, provider));
    }

    @Override
    public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
        return connections.resolve(userId, provider, key).map(ToolConnection.class::cast);
    }
}
