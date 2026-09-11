package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells the {@link UserService} who signed in, but not on every request: it
 * remembers whom it recorded recently and calls the service at most once per
 * {@link UserService#LOGIN_RESOLUTION} per user, so an API client's burst of
 * calls does not become a burst of reads of the user store.
 *
 * <p>A failing user store never fails the request: the caller is already
 * authenticated, the record is bookkeeping.
 */
public class UserRecorder {

    private static final Logger log = LoggerFactory.getLogger(UserRecorder.class);

    private final UserService users;
    private final Clock clock;
    private final Map<UserId, Instant> recent = new ConcurrentHashMap<>();

    public UserRecorder(UserService users) {
        this(users, Clock.systemUTC());
    }

    public UserRecorder(UserService users, Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records a sign-in; details the caller does not know are null. */
    public void record(UserId id, String subject, String issuer, String displayName, String email) {
        Instant now = clock.instant();
        Instant last = recent.get(id);
        if (last != null && Duration.between(last, now).compareTo(UserService.LOGIN_RESOLUTION) < 0) {
            return;
        }
        try {
            users.recordLogin(id, subject, issuer, displayName, email);
            recent.put(id, now);
        } catch (RuntimeException e) {
            log.warn("Could not record the sign-in of {}: {}", id, e.toString());
        }
    }
}
