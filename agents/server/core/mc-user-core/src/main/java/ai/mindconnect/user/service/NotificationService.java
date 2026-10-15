package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationId;
import ai.mindconnect.user.domain.NotificationLevel;
import ai.mindconnect.user.port.out.NotificationRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raising, reading and putting away a user's notifications.
 *
 * <p>The whole point is {@link #raise}: a check that runs on every sign-in
 * says the same thing every time, and the user must see it once, not once per
 * sign-in. A draft with a {@link Notification#key() key} therefore collapses
 * onto the entry that is already open for that key, and a draft whose key the
 * user has <em>dismissed</em> raises nothing at all — they said they know.
 * {@link #resolve} is the other half: when the condition goes away, the entry
 * goes with it, and a later recurrence can raise a fresh one.
 *
 * <p>Changing one user's list is read-modify-write, so it happens under a lock
 * per user, as {@link UserService} does with the user record.
 */
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;
    private final Map<UserId, Object> locks = new ConcurrentHashMap<>();

    public NotificationService(NotificationRepository notifications) {
        this(notifications, Clock.systemUTC());
    }

    public NotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Tells {@code userId} about {@code draft}, unless they have heard it
     * already: an open entry with the same key is left exactly as it is —
     * read or unread, so raising again never turns a read notice back into an
     * unread one — and a dismissed one is not raised again either.
     *
     * @return the entry this call created, empty when the user already had one
     *         for that key. The current entry either way is {@link #withKey}
     */
    public Optional<Notification> raise(UserId userId, Notification.Draft draft) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(draft, "draft");
        synchronized (lockFor(userId)) {
            if (draft.key() != null && withKey(userId, draft.key()).isPresent()) {
                // Open: they have it. Dismissed: they had it and said so.
                return Optional.empty();
            }
            Notification raised = draft.toNotification(userId, clock.instant());
            notifications.save(raised);
            return Optional.of(raised);
        }
    }

    /** {@link #raise} for a whole round of checks; returns what was actually new. */
    public List<Notification> raiseAll(UserId userId, List<Notification.Draft> drafts) {
        if (drafts == null || drafts.isEmpty()) return List.of();
        return drafts.stream().map(draft -> raise(userId, draft)).flatMap(Optional::stream).toList();
    }

    /**
     * The condition behind {@code key} is gone: the user's entry for it is
     * removed — dismissed ones too, so that the same condition coming back
     * raises a fresh notice rather than staying silent.
     *
     * @return true when there was one to remove
     */
    public boolean resolve(UserId userId, String key) {
        Objects.requireNonNull(userId, "userId");
        if (key == null) return false;
        synchronized (lockFor(userId)) {
            List<Notification> hits = notifications.findByUser(userId).stream()
                    .filter(n -> key.equals(n.key()))
                    .toList();
            hits.forEach(n -> notifications.deleteById(n.id()));
            return !hits.isEmpty();
        }
    }

    /**
     * The other half of a check that runs again and again: everything it
     * raised under {@code prefix} that it no longer raises is cleared. What is
     * still in {@code keep} stays exactly as it is — read, unread or dismissed
     * — because the condition behind it has not changed.
     *
     * @return how many entries were cleared
     */
    public int resolveOthers(UserId userId, String prefix, Collection<String> keep) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(prefix, "prefix");
        Set<String> kept = keep == null ? Set.of() : Set.copyOf(keep);
        synchronized (lockFor(userId)) {
            List<Notification> gone = notifications.findByUser(userId).stream()
                    .filter(n -> n.key() != null && n.key().startsWith(prefix) && !kept.contains(n.key()))
                    .toList();
            gone.forEach(n -> notifications.deleteById(n.id()));
            return gone.size();
        }
    }

    /** Everything still on the user's list, newest first. */
    public List<Notification> open(UserId userId) {
        return notifications.findByUser(userId).stream()
                .filter(Notification::open)
                .sorted(newestFirst())
                .toList();
    }

    /** What the bell counts: entries the user has not seen yet. */
    public long unreadCount(UserId userId) {
        return notifications.findByUser(userId).stream().filter(Notification::unread).count();
    }

    /** True when something on the list is waiting for the user to act — how the bell decides to insist. */
    public boolean hasActionRequired(UserId userId) {
        return notifications.findByUser(userId).stream()
                .anyMatch(n -> n.open() && n.level() == NotificationLevel.ACTION_REQUIRED);
    }

    /** Marks everything the user has on their list as seen — what opening the panel means. */
    public void markAllRead(UserId userId) {
        Instant now = clock.instant();
        synchronized (lockFor(userId)) {
            notifications.findByUser(userId).stream()
                    .filter(Notification::unread)
                    .forEach(n -> notifications.save(n.readAt(now)));
        }
    }

    /** Puts one entry away; false when the id is not this user's. */
    public boolean dismiss(UserId userId, NotificationId id) {
        Objects.requireNonNull(userId, "userId");
        synchronized (lockFor(userId)) {
            return notifications.findById(id)
                    .filter(n -> n.userId().equals(userId))
                    .map(n -> {
                        notifications.save(n.dismissedAt(clock.instant()));
                        return true;
                    })
                    .orElse(false);
        }
    }

    /** Puts the whole list away at once. */
    public int dismissAll(UserId userId) {
        Instant now = clock.instant();
        synchronized (lockFor(userId)) {
            List<Notification> open = notifications.findByUser(userId).stream().filter(Notification::open).toList();
            open.forEach(n -> notifications.save(n.dismissedAt(now)));
            return open.size();
        }
    }

    /** The user's entry for {@code key}, open or dismissed; empty when they never had one. */
    public Optional<Notification> withKey(UserId userId, String key) {
        if (key == null) return Optional.empty();
        return notifications.findByUser(userId).stream()
                .filter(n -> key.equals(n.key()))
                .max(Comparator.comparing(Notification::createdAt));
    }

    private static Comparator<Notification> newestFirst() {
        return Comparator.comparing(Notification::createdAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private Object lockFor(UserId id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }
}
