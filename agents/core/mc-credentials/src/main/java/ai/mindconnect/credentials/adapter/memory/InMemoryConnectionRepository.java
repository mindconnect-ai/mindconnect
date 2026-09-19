package ai.mindconnect.credentials.adapter.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import ai.mindconnect.credentials.port.out.ConnectionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ConnectionRepository} in a map — for tests and for a host that keeps
 * nothing between restarts.
 */
public class InMemoryConnectionRepository implements ConnectionRepository {

    private final Map<ConnectionId, Connection> byId = new ConcurrentHashMap<>();

    @Override
    public void save(Connection connection) {
        byId.put(connection.id(), connection);
    }

    @Override
    public Optional<Connection> findById(ConnectionId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Connection> findByUser(UserId userId) {
        return byId.values().stream().filter(c -> c.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(ConnectionId id) {
        byId.remove(id);
    }
}
