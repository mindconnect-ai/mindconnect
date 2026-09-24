package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserMemoryRepository} on Postgres: one row of {@code mc_user_memory}
 * per entry, keyed by {@code (namespace, id)} with the id {@code <user>/<name>}
 * — the name is unique per user, not per namespace. {@code user_id} is a
 * column of its own for listing a user's entries. Bound to one namespace;
 * the namespace purge finds the table by its {@code namespace} column.
 */
public final class PgUserMemoryRepository implements UserMemoryRepository {

    private final DocumentTable<MemoryEntry> entries;
    private final Namespace namespace;

    public PgUserMemoryRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgUserMemoryRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.entries = DocumentTable.of(MemoryEntry.class)
                .table("mc_user_memory")
                .partitionKey("namespace", "TEXT", e -> namespace.value())
                .id("id", "TEXT", e -> key(e.userId(), e.name()))
                .requiredColumn("user_id", "TEXT", e -> e.userId().value())
                .index("namespace", "user_id")
                .build(sql);
    }

    public PgUserMemoryRepository initSchema() {
        entries.createSchema();
        return this;
    }

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        return entries.find("WHERE namespace = ? AND user_id = ?", namespace.value(), userId.value());
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, String name) {
        return entries.findById(namespace.value(), key(userId, name));
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        return entries.save(entry);
    }

    @Override
    public boolean delete(UserId userId, String name) {
        return entries.deleteById(namespace.value(), key(userId, name));
    }

    private static String key(UserId userId, String name) {
        return userId.value() + "/" + name;
    }
}
