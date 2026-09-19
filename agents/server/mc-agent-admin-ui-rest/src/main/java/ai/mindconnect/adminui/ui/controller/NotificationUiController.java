package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.NotificationsComponent;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import ai.mindconnect.user.domain.NotificationId;
import ai.mindconnect.user.service.NotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;

/**
 * The notification panel behind the header's bell. Everything here acts on
 * the caller's own notices: an id that is not theirs is answered like one that
 * does not exist.
 *
 * <p>Opening the panel marks what is in it as read — that is what opening it
 * means — so the bell loses its count in the same patch that shows the list.
 * Dismissing keeps the entry but takes it off the list; see
 * {@link ai.mindconnect.user.domain.Notification}.
 */
@RestController
@RequestMapping(NotificationsComponent.API)
@ConditionalOnBean(NotificationService.class)
public class NotificationUiController {

    private final NotificationService notifications;
    private final Clock clock;

    public NotificationUiController(NotificationService notifications) {
        this(notifications, Clock.systemUTC());
    }

    NotificationUiController(NotificationService notifications, Clock clock) {
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Opens the panel with the list as it is, and marks it read. */
    @GetMapping
    public UiPatch open(@AuthenticationPrincipal OidcUser user) {
        UserId id = userId(user);
        var list = notifications.open(id);
        notifications.markAllRead(id);
        UiDialog dialog = UiDialog.of("Notifications", null,
                NotificationsComponent.body(list, clock.instant()));
        dialog.setId(NotificationsComponent.DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(NotificationsComponent.DIALOG_ID))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog))
                .patch(UiPatch.Operation.replace(NotificationsComponent.BADGE_ID, badge(id)));
    }

    @PostMapping("/close")
    public UiPatch close() {
        return UiPatch.of().patch(UiPatch.Operation.remove(NotificationsComponent.DIALOG_ID));
    }

    /** Puts one notice away and redraws the list and the bell in place. */
    @PostMapping("/{id}/dismiss")
    public UiPatch dismiss(@AuthenticationPrincipal OidcUser user, @PathVariable("id") String id) {
        UserId me = userId(user);
        boolean dismissed = notifications.dismiss(me, NotificationId.of(id));
        UiPatch patch = redraw(me);
        return dismissed ? patch
                : patch.toast(UiToast.info("That notification is already gone.").title("Nothing to dismiss"));
    }

    /** Puts the whole list away at once. */
    @PostMapping("/dismiss-all")
    public UiPatch dismissAll(@AuthenticationPrincipal OidcUser user) {
        UserId me = userId(user);
        int count = notifications.dismissAll(me);
        return redraw(me).toast(count == 0
                ? UiToast.info("There was nothing on the list.").title("Nothing to dismiss")
                : UiToast.success(count == 1 ? "One notification dismissed."
                        : count + " notifications dismissed.").title("List cleared"));
    }

    /**
     * The list and the bell as they are now. A notice cleared because its
     * condition went away — a variable that is set at last — disappears on the
     * next sign-in without anyone dismissing it, so this redraw is only ever
     * about what the user just did.
     */
    private UiPatch redraw(UserId user) {
        return UiPatch.of()
                .patch(UiPatch.Operation.replace(NotificationsComponent.BODY_ID,
                        NotificationsComponent.body(notifications.open(user), clock.instant())))
                .patch(UiPatch.Operation.replace(NotificationsComponent.BADGE_ID, badge(user)));
    }

    private ai.mindconnect.ui.model.UiNode badge(UserId user) {
        return NotificationsComponent.badge(notifications.unreadCount(user), notifications.hasActionRequired(user));
    }

    private static UserId userId(OidcUser user) {
        return UserId.of(user == null ? "mc_user" : user.getPreferredUsername());
    }
}
