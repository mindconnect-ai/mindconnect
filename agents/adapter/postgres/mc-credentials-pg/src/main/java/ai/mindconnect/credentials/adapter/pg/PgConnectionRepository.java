package ai.mindconnect.credentials.adapter.pg;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import ai.mindconnect.credentials.port.out.ConnectionRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ConnectionRepository} on Postgres. One row of {@code mc_connection}
 * per attached account, keyed by the id alone — connections are
 * installation-wide like their users — with the owner and the provider beside
 * the document and indexed, because reading one user's accounts for one
 * provider is what every tool call does.
 */
public class PgConnectionRepository implements ConnectionRepository {

    private static final String TABLE = "mc_connection";

    private final DocumentTable<Connection> connections;

    public PgConnectionRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgConnectionRepository(Sql sql) {
        Objects.requireNonNull(sql, "sql");
        this.connections = DocumentTable.of(Connection.class)
                .table(TABLE)
                .id("id", "TEXT", c -> c.id().value())
                .requiredColumn("user_id", "TEXT", c -> c.userId().value())
                .requiredColumn("provider", "TEXT", Connection::provider)
                .index("user_id")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgConnectionRepository initSchema() {
        connections.createSchema();
        return this;
    }

    @Override
    public void save(Connection connection) {
        connections.save(connection);
    }

    @Override
    public Optional<Connection> findById(ConnectionId id) {
        return connections.findById(id.value());
    }

    @Override
    public List<Connection> findByUser(UserId userId) {
        return connections.find("WHERE user_id = ? ORDER BY provider, id", userId.value());
    }

    @Override
    public void deleteById(ConnectionId id) {
        connections.deleteById(id.value());
    }
}
