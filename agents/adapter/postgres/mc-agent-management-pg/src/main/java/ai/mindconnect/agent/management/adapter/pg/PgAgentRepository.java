package ai.mindconnect.agent.management.adapter.pg;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

/**
 * {@link AgentRepository} on Postgres: one row of {@code mc_managed_agent}
 * per agent, keyed by {@code (namespace, id)}, the agent as the document
 * and its name beside it for whoever looks at the table. Every statement
 * matches the namespace of the current call.
 */
public final class PgAgentRepository implements AgentRepository {

    private final DocumentTable<ManagedAgent> agents;
    private final ScopeSupplier scope;

    public PgAgentRepository(Sql sql, ScopeSupplier scope) {
        this.scope = scope;
        this.agents = DocumentTable.of(ManagedAgent.class)
                .table("mc_managed_agent")
                .partitionKey("namespace", "TEXT", agent -> scope.namespace().value())
                .id("id", "TEXT", agent -> agent.id().value())
                .column("name", "TEXT", agent -> agent.definition().name())
                .index("namespace", "name")
                .build(sql);
    }

    /** Creates the table and its index where they are missing. */
    public PgAgentRepository initSchema() {
        agents.createSchema();
        return this;
    }

    @Override
    public ManagedAgent create(ManagedAgent agent) {
        if (!agents.insert(agent)) {
            throw new IllegalStateException("Agent " + agent.id().value() + " exists already");
        }
        return agent;
    }

    @Override
    public Optional<ManagedAgent> find(AgentId id) {
        return agents.findById(scope.namespace().value(), id.value());
    }

    @Override
    public List<ManagedAgent> findAll() {
        return agents.find("WHERE namespace = ? ORDER BY name, id", scope.namespace().value());
    }

    /** Reads the row {@code FOR UPDATE} and writes the change in the same transaction. */
    @Override
    public Optional<ManagedAgent> update(AgentId id, UnaryOperator<ManagedAgent> change) {
        return agents.update(scope.namespace().value(), id.value(), change);
    }

    @Override
    public boolean delete(AgentId id) {
        return agents.deleteById(scope.namespace().value(), id.value());
    }
}
