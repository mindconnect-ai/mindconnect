package ai.mindconnect.user.domain;

import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Objects;

/**
 * Something one user should hear about the next time they look — a step of
 * their setup that is still missing, a job that finished while they were away.
 *
 * <p>Addressed to a person, not to a screen: it is stored, so it survives the
 * sign-out that a toast does not, and it is shown once the user is there to
 * read it. A message about what is happening <em>right now</em> in a page they
 * have open is a toast or a stream frame and does not belong here.
 *
 * <p><b>{@link #key()} is the condition, {@link #id()} is the entry.</b> A
 * check that runs on every sign-in raises the same key every time; the service
 * matches on it and leaves the entry that is already there, so "configure your
 * mailbox" is one line and not one per sign-in. When the condition goes away,
 * whoever raised it {@code resolve}s the key and the entry disappears without
 * the user having to tidy up after it.
 *
 * @param id           the entry's own id
 * @param userId       whose notification it is
 * @param key          the condition it stands for, stable across raisings
 *                     ({@code setup.variable.MC_EMAIL_HOST}); null for a
 *                     one-off that should pile up rather than collapse
 * @param level        how much attention it asks for
 * @param title        one line, the whole point of it
 * @param body         the detail; null when the title is the whole story
 * @param actionLabel  what the button says — "Set it up"; null for nothing to click
 * @param actionHref   where the button leads; null with no label
 * @param createdAt    when it was raised
 * @param readAt       when the user last saw it in the panel; null while unread
 * @param dismissedAt  when the user put it away. A dismissed entry is kept, not
 *                     deleted: it is how the service knows not to raise the same
 *                     key at them again until the condition has been gone in between
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Notification(
        NotificationId id,
        UserId userId,
        String key,
        NotificationLevel level,
        String title,
        String body,
        String actionLabel,
        String actionHref,
        Instant createdAt,
        Instant readAt,
        Instant dismissedAt
) {

    public Notification {
        Objects.requireNonNull(id, "A notification needs an id");
        Objects.requireNonNull(userId, "A notification needs a user");
        Objects.requireNonNull(title, "A notification needs a title");
        if (level == null) level = NotificationLevel.INFO;
        if (key != null && key.isBlank()) key = null;
    }

    /** True while the user has neither read nor put it away. */
    public boolean unread() {
        return readAt == null && dismissedAt == null;
    }

    /** True while it is still on the user's list — read or not. */
    public boolean open() {
        return dismissedAt == null;
    }

    /** True when there is a button to render. */
    public boolean hasAction() {
        return actionHref != null && !actionHref.isBlank() && actionLabel != null && !actionLabel.isBlank();
    }

    /** This notification as seen at {@code at}; already-read ones keep the time they were first read. */
    public Notification readAt(Instant at) {
        return readAt != null ? this
                : new Notification(id, userId, key, level, title, body, actionLabel, actionHref, createdAt, at, dismissedAt);
    }

    /** This notification put away at {@code at}. */
    public Notification dismissedAt(Instant at) {
        return dismissedAt != null ? this
                : new Notification(id, userId, key, level, title, body, actionLabel, actionHref, createdAt,
                        readAt != null ? readAt : at, at);
    }

    /**
     * What a raiser writes: the same thing without the parts only the store
     * decides — who it is for, which entry it becomes, and when. A check that
     * runs on sign-in returns these and knows nothing about ids.
     *
     * @param key         the condition; see {@link Notification#key()}
     * @param level       how much attention it asks for; null is {@link NotificationLevel#INFO}
     * @param title       one line
     * @param body        the detail, or null
     * @param actionLabel what the button says, or null
     * @param actionHref  where it leads, or null
     */
    public record Draft(String key, NotificationLevel level, String title, String body,
                        String actionLabel, String actionHref) {

        public Draft {
            Objects.requireNonNull(title, "A notification needs a title");
            if (level == null) level = NotificationLevel.INFO;
        }

        /** A draft with nothing to click. */
        public static Draft of(String key, NotificationLevel level, String title, String body) {
            return new Draft(key, level, title, body, null, null);
        }

        /** This draft with a button. */
        public Draft action(String label, String href) {
            return new Draft(key, level, title, body, label, href);
        }

        /** This draft as an entry for {@code userId}, raised at {@code at}. */
        public Notification toNotification(UserId userId, Instant at) {
            return new Notification(NotificationId.random(), userId, key, level, title, body,
                    actionLabel, actionHref, at, null, null);
        }
    }
}
