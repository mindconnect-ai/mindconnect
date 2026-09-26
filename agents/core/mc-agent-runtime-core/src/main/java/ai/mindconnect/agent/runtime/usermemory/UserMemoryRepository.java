package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the memory entries of users. Bound to one namespace,
 * like every repository of the runtime: the same user has a memory of their
 * own in each namespace. {@code agentId} {@code null} addresses the user's
 * own memory, an agent's id what that agent keeps about the user; a name is
 * unique within one of those.
 */
public interface UserMemoryRepository {

    /** All entries of the user — their own and every agent's — in no particular order. */
    List<MemoryEntry> findByUser(UserId userId);

    /** Every entry of every user in the namespace, in no particular order. */
    List<MemoryEntry> findAll();

    Optional<MemoryEntry> find(UserId userId, AgentId agentId, String name);

    /** Creates or replaces the entry under its user, agent and name. */
    MemoryEntry save(MemoryEntry entry);

    /** {@code false} when there was no such entry. */
    boolean delete(UserId userId, AgentId agentId, String name);
}
