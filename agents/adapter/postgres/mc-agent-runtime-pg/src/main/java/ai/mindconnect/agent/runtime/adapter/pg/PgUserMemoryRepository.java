package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.ScopeSupplier;
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
 * per entry, keyed by {@code (namespace, id)}. The id is {@code <user>/<name>}
 * for the user's own memory and {@code <user>/@<agent>/<name>} for what one
 * agent keeps about them — a name is kebab-case, so the {@code @} cannot
 * collide, and the rows written before agents had a memory keep their ids.
 * {@code user_id} and {@code agent_id} are columns of their own for the
 * listings. Works in the namespace the scope names per call; the namespace purge finds the table by
 * its {@code namespace} column.
 */
public final class PgUserMemoryRepository implements UserMemoryRepository {

    private final DocumentTable<MemoryEntry> entries;
    private final ScopeSupplier scope;

    public PgUserMemoryRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    public PgUserMemoryRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.entries = DocumentTable.of(MemoryEntry.class)
                .table("mc_user_memory")
                .partitionKey("namespace", "TEXT", e -> scope.namespace().value())
                .id("id", "TEXT", e -> key(e.userId(), e.agentId(), e.name()))
                .requiredColumn("user_id", "TEXT", e -> e.userId().value())
                .column("agent_id", "TEXT", e -> e.agentId() == null ? null : e.agentId().value())
                .index("namespace", "user_id")
                .build(sql);
    }

    public PgUserMemoryRepository initSchema() {
        entries.createSchema();
        return this;
    }

    @Override
    public List<MemoryEntry> findByUser(UserId userId) {
        return entries.find("WHERE namespace = ? AND user_id = ?", scope.namespace().value(), userId.value());
    }

    @Override
    public List<MemoryEntry> findAll() {
        return entries.find("WHERE namespace = ?", scope.namespace().value());
    }

    @Override
    public Optional<MemoryEntry> find(UserId userId, AgentId agentId, String name) {
        return entries.findById(scope.namespace().value(), key(userId, agentId, name));
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        return entries.save(entry);
    }

    @Override
    public boolean delete(UserId userId, AgentId agentId, String name) {
        return entries.deleteById(scope.namespace().value(), key(userId, agentId, name));
    }

    private static String key(UserId userId, AgentId agentId, String name) {
        return agentId == null ? userId.value() + "/" + name : userId.value() + "/@" + agentId.value() + "/" + name;
    }
}
