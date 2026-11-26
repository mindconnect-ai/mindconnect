package ai.mindconnect.agent.management.api;

import ai.mindconnect.agent.AgentId;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Keeps the agents: what a user interface lists, edits and deletes, and
 * where the runtime finds an agent another agent calls by name.
 *
 * <p>Works in the namespace of whoever calls. A name is unique among the
 * agents of a namespace; the id stays when the name changes.
 */
public interface AgentManagement {

    /** Every agent, by name. */
    List<ManagedAgent> list();

    Optional<ManagedAgent> find(AgentId id);

    Optional<ManagedAgent> findByName(String name);

    /**
     * Stores a new agent. An id the argument brings is kept — an agent read
     * from a file stays the one it was — and one is given here when it brings
     * none. Its times and its version are given here; whatever the argument
     * says of them is ignored.
     *
     * @throws IllegalArgumentException if another agent has the name
     * @throws IllegalStateException    if there is an agent of that id already
     */
    ManagedAgent create(ManagedAgent agent);

    /**
     * Changes an agent that is still the version the change was made from.
     *
     * @param expectedVersion the version the caller read
     * @throws IllegalArgumentException if there is no such agent, or the
     *         change gives it a name another agent has
     * @throws java.util.ConcurrentModificationException if the agent was
     *         changed since: the caller reads it again and decides
     */
    ManagedAgent update(AgentId id, long expectedVersion, UnaryOperator<ManagedAgent> change);

    /** A new agent like this one, under a name that is free. */
    ManagedAgent copy(AgentId id);

    /** Deletes the agent; one that is not there is none. Sessions that carry its definition keep it. */
    void delete(AgentId id);
}
