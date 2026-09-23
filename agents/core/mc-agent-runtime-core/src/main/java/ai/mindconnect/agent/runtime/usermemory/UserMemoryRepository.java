package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.UserId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the memory entries of users. Bound to one namespace,
 * like every repository of the runtime: the same user has a memory of their
 * own in each namespace.
 */
public interface UserMemoryRepository {

    /** All entries of the user, in no particular order; empty when there are none. */
    List<MemoryEntry> findByUser(UserId userId);

    Optional<MemoryEntry> find(UserId userId, String name);

    /** Creates or replaces the entry under its user and name. */
    MemoryEntry save(MemoryEntry entry);

    /** {@code false} when there was no such entry. */
    boolean delete(UserId userId, String name);
}
