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

    /**
     * Loads the window, changes it and saves it back as one step — nothing
     * when there is none. What the actions write through is such a change,
     * and two of them at once must not each save their own copy over the
     * other's.
     *
     * <p>The default is a plain load and save, for a store that has nothing
     * better; the shipped ones hold a lock around it.
     */
    default void update(UserId user, Location location, java.util.function.UnaryOperator<FolderWindow> change) {
        load(user, location).ifPresent(w -> {
            FolderWindow changed = change.apply(w);
            if (changed != w) save(user, changed);
        });
    }

    /** Every window a user has — for the profile's "what is kept about me", and to drop them all. */
    List<Location> windows(UserId user);
}
