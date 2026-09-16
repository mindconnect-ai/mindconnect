package ai.mindconnect.user.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the user records in step with who signs in. It is called on every
 * authenticated request that carries identity details — a browser login, a
 * bearer JWT — so it writes only when a detail changed or the recorded login
 * is older than {@link #LOGIN_RESOLUTION}: a busy API client must not turn
 * every call into a write.
 */
public class UserService {

    /** How stale {@link User#lastLoginAt()} may get before a request records a new one. */
    public static final Duration LOGIN_RESOLUTION = Duration.ofMinutes(5);

    private final UserRepository users;
    private final Clock clock;
    /** Every change of a record is read-modify-write of the whole document: one at a time per user, in this process. */
    private final Map<UserId, Object> locks = new ConcurrentHashMap<>();

    public UserService(UserRepository users) {
        this(users, Clock.systemUTC());
    }

    public UserService(UserRepository users, Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates the user on first sight, otherwise brings the stored details up
     * to date. A detail the provider did not send this time (null) keeps its
     * stored value.
     *
     * @return the user as stored after the call
     */
    public User recordLogin(UserId id, String subject, String issuer, String displayName, String email) {
        Objects.requireNonNull(id, "id");
        synchronized (lockFor(id)) {
            return recordLoginLocked(id, subject, issuer, displayName, email);
        }
    }

    private User recordLoginLocked(UserId id, String subject, String issuer, String displayName, String email) {
        Instant now = clock.instant();
        User existing = users.findById(id).orElse(null);
        if (existing == null) {
            User created = new User(id, subject, issuer, displayName, email, now, now);
            users.save(created);
            return created;
        }
        User merged = new User(id,
                orElse(subject, existing.subject()),
                orElse(issuer, existing.issuer()),
                orElse(displayName, existing.displayName()),
                orElse(email, existing.email()),
                existing.createdAt(),
                existing.lastLoginAt(),
                existing.activeNamespace(),
                existing.environment());
        boolean changed = !merged.equals(existing);
        boolean stale = existing.lastLoginAt() == null
                || Duration.between(existing.lastLoginAt(), now).compareTo(LOGIN_RESOLUTION) >= 0;
        if (!changed && !stale) {
            return existing;
        }
        User updated = new User(id, merged.subject(), merged.issuer(), merged.displayName(), merged.email(),
                merged.createdAt() != null ? merged.createdAt() : now, now, merged.activeNamespace(),
                merged.environment());
        users.save(updated);
        return updated;
    }

    public Optional<User> find(UserId id) {
        return users.findById(id);
    }

    /**
     * Remembers the namespace {@code id} chose to work in. A user the
     * installation has not seen sign in yet gets a record for it.
     */
    public void selectNamespace(UserId id, Namespace namespace) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(namespace, "namespace");
        synchronized (lockFor(id)) {
            User existing = users.findById(id).orElse(null);
            if (existing == null) {
                Instant now = clock.instant();
                users.save(new User(id, null, null, null, null, now, now, namespace.value()));
                return;
            }
            if (!namespace.value().equals(existing.activeNamespace())) {
                users.save(existing.withActiveNamespace(namespace.value()));
            }
        }
    }

    /** The namespace {@code id} last chose, if they ever chose one. */
    public Optional<Namespace> activeNamespace(UserId id) {
        return users.findById(id).map(User::activeNamespace)
                .filter(value -> value != null && !value.isBlank())
                .map(Namespace::new);
    }

    /**
     * Replaces the variables {@code id} keeps for themselves with exactly
     * {@code environment} — every name with a value. A user the installation
     * has not seen sign in yet gets a record for it. Values go to the
     * repository in plain; an encrypting repository decorator makes them
     * {@code enc:} at rest.
     */
    public User setEnvironment(UserId id, Map<String, String> environment) {
        Objects.requireNonNull(id, "id");
        EnvVarResolver.requireValid(environment);
        synchronized (lockFor(id)) {
            return saveEnvironment(id, environment);
        }
    }

    /** Adds the variable {@code name} to {@code id}'s own, or replaces its value; the merge happens under the user's lock. */
    public User putVariable(UserId id, String name, String value) {
        Objects.requireNonNull(id, "id");
        EnvVarResolver.requireValid(name, value);
        synchronized (lockFor(id)) {
            Map<String, String> merged = new LinkedHashMap<>(environment(id));
            merged.put(name, value);
            return saveEnvironment(id, merged);
        }
    }

    /** Removes the variable {@code name} from {@code id}'s own; false when they had none of that name. */
    public boolean removeVariable(UserId id, String name) {
        Objects.requireNonNull(id, "id");
        synchronized (lockFor(id)) {
            Map<String, String> merged = new LinkedHashMap<>(environment(id));
            if (merged.remove(name) == null) return false;
            saveEnvironment(id, merged);
            return true;
        }
    }

    private User saveEnvironment(UserId id, Map<String, String> environment) {
        User existing = users.findById(id).orElse(null);
        User updated = existing == null
                ? new User(id, null, null, null, null, clock.instant(), clock.instant(), null, environment)
                : existing.withEnvironment(environment);
        users.save(updated);
        return updated;
    }

    /** The variables {@code id} keeps for themselves, as stored — empty for a user the installation does not know. */
    public Map<String, String> environment(UserId id) {
        return users.findById(id).map(User::environment).orElse(Map.of());
    }

    private Object lockFor(UserId id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }

    private static String orElse(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
