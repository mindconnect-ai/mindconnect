package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Preferences;
import ai.mindconnect.user.port.out.PreferenceRepository;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reading and writing what a user's screens remember.
 *
 * <p>Written on every navigation — a folder opened, a view switched — so a
 * write that changes nothing writes nothing, and changing one value is a
 * merge, not a replace: a screen that remembers its folder does not forget
 * the sort order another part of it stored in the same scope.
 *
 * <p>Bounded, because it is written from requests: at most
 * {@value #MAX_KEYS} keys a scope and {@value #MAX_VALUE} characters a value.
 * Scopes and keys are checked against {@link Preferences#SCOPE} and
 * {@link Preferences#KEY}, which is also what keeps them safe as a file name.
 *
 * <p>Read-modify-write happens under a lock per user, as in
 * {@link NotificationService}.
 */
public class PreferenceService {

    public static final int MAX_KEYS = 100;
    public static final int MAX_VALUE = 4096;

    private final PreferenceRepository preferences;
    private final Clock clock;
    private final Map<UserId, Object> locks = new ConcurrentHashMap<>();

    public PreferenceService(PreferenceRepository preferences) {
        this(preferences, Clock.systemUTC());
    }

    public PreferenceService(PreferenceRepository preferences, Clock clock) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Everything the scope remembers; empty when it remembers nothing. */
    public Preferences get(UserId userId, String scope) {
        Objects.requireNonNull(userId, "userId");
        return preferences.find(userId, checkScope(scope)).orElseGet(() -> Preferences.empty(userId, scope));
    }

    /** One value, if the scope has it. */
    public Optional<String> get(UserId userId, String scope, String key) {
        return Optional.ofNullable(get(userId, scope).get(checkKey(key)));
    }

    /** Sets one value; {@code null} removes it. */
    public Preferences put(UserId userId, String scope, String key, String value) {
        Map<String, String> change = new LinkedHashMap<>();
        change.put(checkKey(key), value);
        return merge(userId, scope, change);
    }

    /**
     * Sets several values at once — a folder and the mailbox it is in, which
     * mean nothing apart. A {@code null} value removes its key; keys not
     * named keep their value. Writes only when something changed.
     *
     * @throws IllegalArgumentException for a bad scope or key, a value over
     *         {@value #MAX_VALUE} characters, or a scope past {@value #MAX_KEYS} keys
     */
    public Preferences merge(UserId userId, String scope, Map<String, String> change) {
        Objects.requireNonNull(userId, "userId");
        checkScope(scope);
        Objects.requireNonNull(change, "change");
        change.forEach((key, value) -> {
            checkKey(key);
            if (value != null && value.length() > MAX_VALUE) {
                throw new IllegalArgumentException("A preference value is at most " + MAX_VALUE
                        + " characters; " + key + " has " + value.length() + ".");
            }
        });
        synchronized (locks.computeIfAbsent(userId, id -> new Object())) {
            Preferences current = preferences.find(userId, scope).orElseGet(() -> Preferences.empty(userId, scope));
            Map<String, String> next = new LinkedHashMap<>(current.values());
            change.forEach((key, value) -> {
                if (value == null) next.remove(key);
                else next.put(key, value);
            });
            if (next.equals(current.values())) {
                return current;
            }
            if (next.size() > MAX_KEYS) {
                throw new IllegalArgumentException("A preference scope holds at most " + MAX_KEYS + " keys.");
            }
            if (next.isEmpty()) {
                preferences.delete(userId, scope);
                return Preferences.empty(userId, scope);
            }
            Preferences saved = new Preferences(userId, scope, next, clock.instant());
            preferences.save(saved);
            return saved;
        }
    }

    /** Forgets the scope. */
    public void clear(UserId userId, String scope) {
        Objects.requireNonNull(userId, "userId");
        synchronized (locks.computeIfAbsent(userId, id -> new Object())) {
            preferences.delete(userId, checkScope(scope));
        }
    }

    /** Every scope the user has something in — for an export, or a "forget everything". */
    public List<Preferences> all(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        return preferences.findByUser(userId);
    }

    private static String checkScope(String scope) {
        if (scope == null || !Preferences.SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("A preference scope is lower case letters, digits, '.', '-' "
                    + "or '_', at most 64 characters: " + scope);
        }
        return scope;
    }

    private static String checkKey(String key) {
        if (key == null || !Preferences.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("A preference key is letters, digits, '.', '-' or '_', "
                    + "at most 64 characters: " + key);
        }
        return key;
    }
}
