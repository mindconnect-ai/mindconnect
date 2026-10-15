package ai.mindconnect.user.adapter.memory;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationId;
import ai.mindconnect.user.port.out.NotificationRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link NotificationRepository} in a map — for tests, and for a host that
 * keeps nothing between restarts. What is raised there is gone with the
 * process, which for a notice that is re-raised on every sign-in is no loss.
 */
public class InMemoryNotificationRepository implements NotificationRepository {

    private final Map<NotificationId, Notification> byId = new ConcurrentHashMap<>();

    @Override
    public void save(Notification notification) {
        byId.put(notification.id(), notification);
    }

    @Override
    public Optional<Notification> findById(NotificationId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Notification> findByUser(UserId userId) {
        return byId.values().stream().filter(n -> n.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(NotificationId id) {
        byId.remove(id);
    }
}
