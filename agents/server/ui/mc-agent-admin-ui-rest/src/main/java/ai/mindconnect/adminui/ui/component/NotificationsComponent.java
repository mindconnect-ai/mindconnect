package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationLevel;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The notification centre's two faces: the bell in the header (how many
 * notices are waiting, a click opens the panel) and the panel itself.
 *
 * <p>Shaped like {@link TaskMonitorComponent} and for the same reason — both
 * are addressed by fixed ids, so the same ids can be replaced in place after
 * a dismiss without rendering the page again. The difference is what they are
 * about: the task badge says what the server is doing right now and is live;
 * the bell says what is waiting for <em>this user</em> and changes only when
 * they act, so it is rendered per page rather than streamed.
 */
public final class NotificationsComponent {

    public static final String BADGE_ID = "notifications-badge";
    public static final String DIALOG_ID = "notifications-dialog";
    public static final String BODY_ID = "notifications-body";

    public static final String API = "/admin/api/notifications";

    private NotificationsComponent() { }

    // ── header bell ─────────────────────────────────────────────────────────

    /**
     * The bell. The count is a sibling of the button rather than its label,
     * for the reason {@link TaskMonitorComponent#badge} gives: the button
     * keeps focus after opening the panel and the morpher then leaves its
     * children alone, so a count inside it would freeze.
     *
     * @param unread         how many notices the user has not seen
     * @param actionRequired whether one of them is waiting for them to do something
     */
    public static UiStack badge(long unread, boolean actionRequired) {
        UiAction open = UiAction.link(BADGE_ID + "-link", "Notifications").icon("bell")
                .appearance(UiAction.Appearance.ICON)
                .dispatch("GET", API);
        UiStack badge = UiStack.of(BADGE_ID).direction(UiStack.Direction.HORIZONTAL).gap(4).child(open);
        if (unread > 0) {
            badge.child(count(unread));
        }
        badge.setOnClick(UiTrigger.api("GET", API));
        badge.setTitle(unread == 0
                ? "Notifications — nothing new"
                : unread + (unread == 1 ? " notification" : " notifications") + " you have not read");
        badge.withCssClass("notifications-badge"
                + (unread > 0 ? " is-unread" : "")
                + (actionRequired ? " is-action-required" : ""));
        return badge;
    }

    private static UiText count(long unread) {
        UiText text = UiText.of(BADGE_ID + "-count", unread > 99 ? "99+" : String.valueOf(unread));
        text.withCssClass("notifications-count");
        return text;
    }

    // ── panel ───────────────────────────────────────────────────────────────

    /** The panel: every notice still on the user's list, newest first, with the way to clear it. */
    public static UiStack body(List<Notification> notifications, Instant now) {
        UiStack body = UiStack.of(BODY_ID).gap(12);
        if (notifications.isEmpty()) {
            UiText empty = UiText.of(BODY_ID + "-empty", "Nothing waiting. Notices about your setup show up here.");
            empty.withCssClass("sui-hint");
            body.child(empty);
        } else {
            for (Notification notification : notifications) {
                body.child(entry(notification, now));
            }
        }
        UiStack actions = UiStack.of(BODY_ID + "-actions").direction(UiStack.Direction.HORIZONTAL).gap(8);
        if (!notifications.isEmpty()) {
            actions.child(UiAction.secondary(BODY_ID + "-dismiss-all", "Dismiss all").icon("check")
                    .dispatch("POST", API + "/dismiss-all"));
        }
        actions.child(UiAction.secondary(BODY_ID + "-close", "Close").icon("close")
                .dispatch("POST", API + "/close"));
        body.child(actions);
        return body;
    }

    /** One notice: what it is about, since when, what to do about it, and how to put it away. */
    static UiStack entry(Notification notification, Instant now) {
        String id = "notification-" + notification.id().value();
        UiStack entry = UiStack.of(id).gap(4);
        entry.withCssClass("notification-entry " + levelClass(notification.level())
                + (notification.unread() ? " is-unread" : ""));

        UiText title = UiText.of(id + "-title", notification.title());
        title.withCssClass("notification-title");
        entry.child(title);

        if (notification.body() != null && !notification.body().isBlank()) {
            UiText text = UiText.of(id + "-body", notification.body());
            text.withCssClass("notification-body");
            entry.child(text);
        }

        UiText when = UiText.of(id + "-when", ago(notification.createdAt(), now));
        when.withCssClass("sui-hint notification-when");
        entry.child(when);

        UiStack actions = UiStack.of(id + "-actions").direction(UiStack.Direction.HORIZONTAL).gap(8);
        if (notification.hasAction()) {
            actions.child(UiLink.of(id + "-go", notification.actionHref(), notification.actionLabel()));
        }
        actions.child(UiAction.secondary(id + "-dismiss", "Dismiss").icon("close")
                .appearance(UiAction.Appearance.ICON)
                .dispatch("POST", API + "/" + notification.id().value() + "/dismiss"));
        entry.child(actions);
        return entry;
    }

    private static String levelClass(NotificationLevel level) {
        return switch (level) {
            case ACTION_REQUIRED -> "is-action-required";
            case WARNING -> "is-warning";
            case INFO -> "is-info";
        };
    }

    /** "just now", "3 hours ago" — the time itself is never what the reader wants here. */
    static String ago(Instant at, Instant now) {
        if (at == null || now == null) return "";
        Duration since = Duration.between(at, now);
        if (since.isNegative() || since.toMinutes() < 1) return "just now";
        if (since.toHours() < 1) return plural(since.toMinutes(), "minute") + " ago";
        if (since.toDays() < 1) return plural(since.toHours(), "hour") + " ago";
        return plural(since.toDays(), "day") + " ago";
    }

    private static String plural(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}
