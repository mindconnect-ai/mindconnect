package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Preferences;
import ai.mindconnect.user.port.out.PreferenceRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PreferenceRepository} on the file system: one JSON document per user
 * and scope under {@code <storageDir>/system/preferences/<user>/<scope>.json}
 * — installation-wide, beside the users. The user's directory is named the way
 * {@link FileUserRepository} names the user's file; the scope is safe as a file
 * name because {@link Preferences#SCOPE} allows nothing else.
 *
 * <p>Writes go through one lock, as in {@link FileNotificationRepository}.
 */
public class FilePreferenceRepository implements PreferenceRepository {

    private final Path root;
    private final Object lock = new Object();

    public FilePreferenceRepository(Path storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        this.root = storageDir.resolve("system").resolve("preferences");
    }

    @Override
    public Optional<Preferences> find(UserId userId, String scope) {
        return documents(userId).read(checked(scope))
                .filter(p -> p.userId().equals(userId) && p.scope().equals(scope));
    }

    @Override
    public List<Preferences> findByUser(UserId userId) {
        return documents(userId).readAll().stream().filter(p -> p.userId().equals(userId)).toList();
    }

    @Override
    public void save(Preferences preferences) {
        synchronized (lock) {
            documents(preferences.userId()).write(checked(preferences.scope()), preferences);
        }
    }

    @Override
    public void delete(UserId userId, String scope) {
        synchronized (lock) {
            documents(userId).delete(checked(scope));
        }
    }

    private FileDocuments<Preferences> documents(UserId userId) {
        return new FileDocuments<>(root.resolve(FileUserRepository.fileKey(userId)), Preferences.class);
    }

    /** A scope becomes a file name: nothing but what {@link Preferences#SCOPE} allows gets that far. */
    private static String checked(String scope) {
        if (scope == null || !Preferences.SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("Not a preference scope: " + scope);
        }
        return scope;
    }
}
