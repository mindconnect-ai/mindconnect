package ai.mindconnect.credentials.port.out;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;

import java.util.List;
import java.util.Optional;

/**
 * The accounts the users of this installation have attached. Installation-wide
 * like the users themselves; an adapter names no namespace.
 */
public interface ConnectionRepository {

    /** Inserts or replaces the connection with this id. */
    void save(Connection connection);

    Optional<Connection> findById(ConnectionId id);

    /** Everything one user has attached, in no particular order. */
    List<Connection> findByUser(UserId userId);

    /** Removes the connection; a missing id is not an error. */
    void deleteById(ConnectionId id);
}
