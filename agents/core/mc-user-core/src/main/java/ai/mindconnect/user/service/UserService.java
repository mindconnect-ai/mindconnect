package ai.mindconnect.user.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
                existing.activeNamespace());
        boolean changed = !merged.equals(existing);
        boolean stale = existing.lastLoginAt() == null
                || Duration.between(existing.lastLoginAt(), now).compareTo(LOGIN_RESOLUTION) >= 0;
        if (!changed && !stale) {
            return existing;
        }
        User updated = new User(id, merged.subject(), merged.issuer(), merged.displayName(), merged.email(),
                merged.createdAt() != null ? merged.createdAt() : now, now, merged.activeNamespace());
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

    /** The namespace {@code id} last chose, if they ever chose one. */
    public Optional<Namespace> activeNamespace(UserId id) {
        return users.findById(id).map(User::activeNamespace)
                .filter(value -> value != null && !value.isBlank())
                .map(Namespace::new);
    }

    private static String orElse(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
