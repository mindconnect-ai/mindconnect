package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs every {@link SetupCheck} for one user and keeps their notifications in
 * step with the answer: what a check reports is raised, what it has stopped
 * reporting is cleared.
 *
 * <p>That second half is what makes this safe to run on every sign-in. A user
 * who configures their mailbox does not have to go and tidy the notice away —
 * the next pass finds the variable set, stops reporting it, and the entry
 * goes. And because clearing removes the entry rather than dismissing it, the
 * same condition coming back later — a key revoked, a variable removed —
 * raises a fresh notice instead of staying quiet.
 *
 * <p>Failure is never fatal: a check that throws is logged and skipped. A
 * user must be able to sign in to an installation whose mail server is down.
 */
@Service
public class UserSetup {

    private static final Logger log = LoggerFactory.getLogger(UserSetup.class);

    private final List<SetupCheck> checks;
    /** Absent on a host that keeps no notifications — then there is nothing to raise into and the pass is skipped. */
    private final ObjectProvider<NotificationService> notifications;

    public UserSetup(ObjectProvider<SetupCheck> checks, ObjectProvider<NotificationService> notifications) {
        this.checks = checks.orderedStream().toList();
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    /**
     * One pass over every check for {@code user}.
     *
     * @return how many notices are new — nothing they had already counts
     */
    public int run(UserId user) {
        Objects.requireNonNull(user, "user");
        NotificationService notifications = this.notifications.getIfAvailable();
        if (notifications == null || checks.isEmpty()) {
            return 0;
        }
        int raised = 0;
        for (SetupCheck check : checks) {
            try {
                List<Notification.Draft> pending = check.run(user);
                Set<String> keys = pending.stream().map(Notification.Draft::key)
                        .filter(Objects::nonNull).collect(Collectors.toSet());
                notifications.resolveOthers(user, check.keyPrefix(), keys);
                raised += notifications.raiseAll(user, pending).size();
            } catch (RuntimeException e) {
                log.warn("Setup check {} failed for {} — skipped", check.getClass().getSimpleName(), user.value(), e);
            }
        }
        return raised;
    }
}
