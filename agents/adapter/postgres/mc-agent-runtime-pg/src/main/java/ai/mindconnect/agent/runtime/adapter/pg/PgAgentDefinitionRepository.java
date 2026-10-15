package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AgentDefinitionRepository} on Postgres: one row of
 * {@code mc_agent_definition} per definition, keyed by {@code (namespace, id)},
 * with the name beside the document. {@link #findByName} is case-insensitive,
 * as the file store's is.
 *
 * <p>Every row it writes carries the namespace the scope names for the
 * current call, and every statement it runs matches it, so definitions of
 * another namespace in the same table are invisible here.
 */
public final class PgAgentDefinitionRepository implements AgentDefinitionRepository {

    private final DocumentTable<AgentDefinition> definitions;
    private final ScopeSupplier scope;

    public PgAgentDefinitionRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    public PgAgentDefinitionRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.definitions = DocumentTable.of(AgentDefinition.class)
                .table("mc_agent_definition")
                .partitionKey("namespace", "TEXT", d -> scope.namespace().value())
                .id("id", "TEXT", d -> d.id().value())
                .requiredColumn("name", "TEXT", AgentDefinition::name)
                .index("namespace", "name")
                .build(sql);
    }

    public PgAgentDefinitionRepository initSchema() {
        definitions.createSchema();
        return this;
    }

    @Override
    /** Checks the version against the row read {@code FOR UPDATE} and stores it one higher, in one transaction. */
    public AgentDefinition save(AgentDefinition definition) {
        return definitions.compute(scope.namespace().value(), definition.id().value(), current ->
                definition.withVersion(ai.mindconnect.common.Versions.next(
                        current.map(AgentDefinition::version).orElse(null), definition.version(),
                        "AgentDefinition", definition.id().value())));
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        return definitions.findById(scope.namespace().value(), id.value());
    }

    @Override
    public List<AgentDefinition> findAll() {
        return definitions.find("WHERE namespace = ? ORDER BY name", scope.namespace().value());
    }

    @Override
    public Optional<AgentDefinition> findByName(String name) {
        return definitions.findOne("WHERE namespace = ? AND lower(name) = lower(?) ORDER BY updated_at LIMIT 1",
                scope.namespace().value(), name);
    }

    @Override
    public void deleteById(AgentId id) {
        definitions.deleteById(scope.namespace().value(), id.value());
    }
}
