package ai.mindconnect.user.port.out;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationId;

import java.util.List;
import java.util.Optional;

/**
 * What each user still has to hear. Notifications are installation-wide like
 * their users; an adapter names no namespace.
 */
public interface NotificationRepository {

    /** Inserts or replaces the notification with this id. */
    void save(Notification notification);

    Optional<Notification> findById(NotificationId id);

    /** Everything raised for a user, dismissed entries included, in no particular order. */
    List<Notification> findByUser(UserId userId);

    /** Removes the notification; a missing id is not an error. */
    void deleteById(NotificationId id);
}
