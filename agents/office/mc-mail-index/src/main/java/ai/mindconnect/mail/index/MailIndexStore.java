package ai.mindconnect.mail.index;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Location;

import java.util.List;
import java.util.Optional;

/**
 * Where the windows are kept: one per user, account and folder. A file per
 * window in the file adapter — small, a thousand heads being half a
 * megabyte — and in {@link PgMailIndexStore} a row for the window and a
 * row per head.
 */
public interface MailIndexStore {

    Optional<FolderWindow> load(UserId user, Location location);

    void save(UserId user, FolderWindow window);

    void delete(UserId user, Location location);

    /** Every window a user has — for the profile's "what is kept about me", and to drop them all. */
    List<Location> windows(UserId user);
}
