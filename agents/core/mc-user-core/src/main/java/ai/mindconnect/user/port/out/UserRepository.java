package ai.mindconnect.user.port.out;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;

import java.util.List;
import java.util.Optional;

/**
 * The users of this installation. An adapter is bound to one namespace when it
 * is built; nothing here names it.
 */
public interface UserRepository {

    Optional<User> findById(UserId id);

    /** Every user, in no particular order. */
    List<User> findAll();

    /** Inserts or replaces the user with this id. */
    void save(User user);
}
