package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AgentDefinitionRepository} on Postgres: one row of
 * {@code mc_agent_definition} per definition, namespace and name beside the
 * document. {@link #findByName} is case-insensitive, as the file store's is.
 */
public final class PgAgentDefinitionRepository implements AgentDefinitionRepository {

    private final DocumentTable<AgentDefinition> definitions;

    public PgAgentDefinitionRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgAgentDefinitionRepository(Sql sql) {
        this.definitions = DocumentTable.of(AgentDefinition.class)
                .table("mc_agent_definition")
                .id("id", "UUID", AgentDefinition::id)
                .requiredColumn("namespace", "TEXT", d -> d.namespace().value())
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
    public Optional<AgentDefinition> findById(UUID id) {
        return definitions.findById(id);
    }

    @Override
    public List<AgentDefinition> findByNamespace(Namespace namespace) {
        return definitions.find("WHERE namespace = ? ORDER BY name", namespace.value());
    }

    @Override
    public Optional<AgentDefinition> findByName(Namespace namespace, String name) {
        return definitions.findOne("WHERE namespace = ? AND lower(name) = lower(?) ORDER BY updated_at LIMIT 1",
                namespace.value(), name);
    }

    @Override
    public void deleteById(UUID id) {
        definitions.deleteById(id);
    }
}
