package ai.mindconnect.user.port.out;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;

import java.util.List;
import java.util.Optional;

/**
 * The tools each user keeps in their own account. Installation-wide like the
 * users themselves; an adapter names no namespace.
 */
public interface UserToolRepository {

    /** Inserts or replaces the binding with this id. */
    void save(UserTool tool);

    Optional<UserTool> findById(UserToolId id);

    /** Everything one user keeps, in no particular order. */
    List<UserTool> findByUser(UserId userId);

    /** Removes the binding; a missing id is not an error. */
    void deleteById(UserToolId id);
}
