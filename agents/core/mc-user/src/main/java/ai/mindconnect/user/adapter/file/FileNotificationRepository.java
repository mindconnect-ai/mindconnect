package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationId;
import ai.mindconnect.user.port.out.NotificationRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link NotificationRepository} on the file system: one JSON document per
 * notification under {@code <storageDir>/system/notifications/<id>.json} —
 * installation-wide, beside the users and their tokens. A lookup by user reads
 * the directory: a person carries a handful of open notices, not millions, and
 * the Postgres adapter indexes the owner.
 *
 * <p>Writes go through one lock, so that raising a notice and dismissing
 * another cannot interleave.
 */
public class FileNotificationRepository implements NotificationRepository {

    private final FileDocuments<Notification> notifications;
    private final Object lock = new Object();

    public FileNotificationRepository(Path storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        this.notifications = new FileDocuments<>(
                storageDir.resolve("system").resolve("notifications"), Notification.class);
    }

    @Override
    public void save(Notification notification) {
        synchronized (lock) {
            notifications.write(notification.id().value(), notification);
        }
    }

    @Override
    public Optional<Notification> findById(NotificationId id) {
        return notifications.read(id.value()).filter(n -> n.id().equals(id));
    }

    @Override
    public List<Notification> findByUser(UserId userId) {
        return notifications.readAll().stream().filter(n -> n.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(NotificationId id) {
        synchronized (lock) {
            notifications.delete(id.value());
        }
    }
}
