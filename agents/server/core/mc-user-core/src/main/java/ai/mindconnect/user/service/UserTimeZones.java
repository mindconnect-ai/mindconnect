package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link TimeZones} from the user records: the zone a user chose on their
 * profile page, or the one their browser reported the first time it was
 * seen ({@link UserService#adoptTimeZone}), else the installation's default
 * ({@code mindconnect.time-zone}, the JVM's zone when unset).
 *
 * <p>Read on every call rather than cached: a tool asks once per call, the
 * prompt once per round, and a zone changed on the profile page applies to
 * the very next one. A user store that fails is not a reason to fail a turn —
 * the default answers and the log says why.
 */
public class UserTimeZones implements TimeZones {

    private static final Logger log = LoggerFactory.getLogger(UserTimeZones.class);

    private final UserService users;
    private final Supplier<ZoneId> fallback;

    /** With the JVM's zone, read per call, as the default. */
    public UserTimeZones(UserService users) {
        this(users, ZoneId::systemDefault);
    }

    /** With {@code fallback} as the installation's default. */
    public UserTimeZones(UserService users, ZoneId fallback) {
        this(users, constant(fallback));
    }

    private UserTimeZones(UserService users, Supplier<ZoneId> fallback) {
        this.users = Objects.requireNonNull(users, "users");
        this.fallback = fallback;
    }

    @Override
    public ZoneId zoneOf(UserId user) {
        if (user == null) return fallback.get();
        String stored;
        try {
            stored = users.find(user).map(User::timeZone).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read the time zone of {}; using the installation's: {}", user.value(), e.toString());
            return fallback.get();
        }
        return TimeZones.parse(stored).orElseGet(fallback);
    }

    /** The installation's default — what a user without a zone of their own gets. */
    public ZoneId fallback() {
        return fallback.get();
    }

    private static Supplier<ZoneId> constant(ZoneId zone) {
        Objects.requireNonNull(zone, "fallback");
        return () -> zone;
    }
}
