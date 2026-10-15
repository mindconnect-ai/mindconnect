package ai.mindconnect.user.port.out;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Preferences;

import java.util.List;
import java.util.Optional;

/**
 * What each user's screens remember, one document per user and scope.
 * Installation-wide like the users themselves; an adapter names no namespace.
 */
public interface PreferenceRepository {

    Optional<Preferences> find(UserId userId, String scope);

    /** Every scope the user has something in, in no particular order. */
    List<Preferences> findByUser(UserId userId);

    /** Inserts or replaces the user's document for {@code preferences.scope()}. */
    void save(Preferences preferences);

    /** Removes the user's document for the scope; a missing one is not an error. */
    void delete(UserId userId, String scope);
}
