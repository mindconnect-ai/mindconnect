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
import java.util.Set;
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
    /** Users known to have a time zone — the browser's report is not looked at again for them in this process. */
    private final Set<UserId> zoned = ConcurrentHashMap.newKeySet();

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

    /**
     * The zone the user's browser reports ({@code Intl.DateTimeFormat().resolvedOptions().timeZone},
     * sent as the {@value #TIME_ZONE_COOKIE} cookie), stored as theirs the first
     * time it is seen — see {@link UserService#adoptTimeZone}. Once a user has a
     * zone, whether chosen or adopted, this is a set lookup: no store is read
     * again for them in this process. Call it after {@link #record}, which
     * creates the record the zone goes into.
     */
    public void offerTimeZone(UserId id, String browserZone) {
        if (id == null || zoned.contains(id)) return;
        // Not a zone this JVM knows: nothing to store, and no reason to read the store for it.
        if (ai.mindconnect.agent.tool.TimeZones.parse(browserZone).isEmpty()) return;
        try {
            if (users.adoptTimeZone(id, browserZone).isPresent()) zoned.add(id);
        } catch (RuntimeException e) {
            log.warn("Could not store the time zone of {}: {}", id, e.toString());
        }
    }

    /** The cookie the Admin UI's shell writes the browser's time zone into. */
    public static final String TIME_ZONE_COOKIE = "mc-time-zone";
}
