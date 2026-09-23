package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;

import java.util.List;
import java.util.Optional;

/**
 * Where views are kept — the port. A file per user is the shipped one; a
 * table is the same interface.
 *
 * <p>Every call is for one user, and a user only ever sees their own: a view
 * of somebody else's mail is not a thing this store can answer.
 */
public interface ViewStore {

    Optional<StoredView> load(UserId user, ViewId id);

    void save(StoredView view);

    void delete(UserId user, ViewId id);

    /** The user's stored views of one kind, newest first; every kind when {@code kind} is null. */
    List<StoredView> list(UserId user, String kind);
}
