package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
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
 * <p>The repository is bound to one namespace: every row it writes carries
 * it, and every statement it runs matches it, so definitions of another
 * namespace in the same table are invisible here.
 */
public final class PgAgentDefinitionRepository implements AgentDefinitionRepository {

    private final DocumentTable<AgentDefinition> definitions;
    private final Namespace namespace;

    public PgAgentDefinitionRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgAgentDefinitionRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.definitions = DocumentTable.of(AgentDefinition.class)
                .table("mc_agent_definition")
                .partitionKey("namespace", "TEXT", d -> namespace.value())
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
    public AgentDefinition save(AgentDefinition definition) {
        return definitions.save(definition);
    }

    @Override
    public Optional<AgentDefinition> findById(AgentId id) {
        return definitions.findById(namespace.value(), id.value());
    }

    @Override
    public List<AgentDefinition> findAll() {
        return definitions.find("WHERE namespace = ? ORDER BY name", namespace.value());
    }

    @Override
    public Optional<AgentDefinition> findByName(String name) {
        return definitions.findOne("WHERE namespace = ? AND lower(name) = lower(?) ORDER BY updated_at LIMIT 1",
                namespace.value(), name);
    }

    @Override
    public void deleteById(AgentId id) {
        definitions.deleteById(namespace.value(), id.value());
    }
}
