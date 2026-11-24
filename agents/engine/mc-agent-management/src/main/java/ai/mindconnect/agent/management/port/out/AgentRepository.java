package ai.mindconnect.agent.management.port.out;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;

/** Where the agents of a namespace are kept. Rules — a free name, the right version — are the management's. */
public interface AgentRepository {

    /** @throws IllegalStateException if an agent of that id exists already */
    ManagedAgent create(ManagedAgent agent);

    Optional<ManagedAgent> find(AgentId id);

    List<ManagedAgent> findAll();

    /**
     * Reads, changes and writes as one step. Empty when there is no such
     * agent; what the change throws comes out and nothing is written.
     */
    Optional<ManagedAgent> update(AgentId id, UnaryOperator<ManagedAgent> change);

    /** Whether there was one. */
    boolean delete(AgentId id);
}
