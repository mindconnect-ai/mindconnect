package ai.mindconnect.credentials.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import ai.mindconnect.credentials.domain.ConnectionState;
import ai.mindconnect.credentials.domain.FormCreds;
import ai.mindconnect.credentials.domain.UserCredentials;
import ai.mindconnect.credentials.port.out.ConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attaching, reading and detaching a user's accounts.
 *
 * <p>Three rules live here rather than in the UI, because a second caller —
 * an OAuth callback, an import — has to follow them too:
 *
 * <ol>
 *   <li><b>Exactly one default per (user, provider).</b> The first connection
 *       becomes it; making another the default takes it off the old one;
 *       removing the default promotes the next.</li>
 *   <li><b>Keys are unique per (user, provider) and never change.</b> A second
 *       "Arbeit" becomes {@code arbeit-2} rather than overwriting the first —
 *       a tool call or a pinned parameter that names {@code arbeit} must keep
 *       meaning the same account.</li>
 *   <li><b>A blank secret means "leave it".</b> A password is never shown
 *       again, so the edit form comes back with an empty field; taking that
 *       literally would wipe the credential on every rename.</li>
 * </ol>
 *
 * <p>Changing a user's connections is read-modify-write across several
 * records, so it happens under a lock per user, as {@code UserService} does.
 */
public class ConnectionService {

    private final ConnectionRepository connections;
    private final Clock clock;
    private final Map<UserId, Object> locks = new ConcurrentHashMap<>();

    public ConnectionService(ConnectionRepository connections) {
        this(connections, Clock.systemUTC());
    }

    public ConnectionService(ConnectionRepository connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── reading ─────────────────────────────────────────────────────────────

    /** What {@code userId} has attached for {@code provider} — the default first, then by label. */
    public List<Connection> of(UserId userId, String provider) {
        Objects.requireNonNull(userId, "userId");
        return connections.findByUser(userId).stream()
                .filter(c -> c.provider().equals(provider))
                .sorted(defaultFirst())
                .toList();
    }

    /** Everything {@code userId} has attached, grouped the same way. */
    public List<Connection> all(UserId userId) {
        return connections.findByUser(userId).stream()
                .sorted(Comparator.comparing(Connection::provider).thenComparing(defaultFirst()))
                .toList();
    }

    /**
     * The connection a call should use: the one named by {@code key}, or the
     * user's default for that provider when {@code key} is null or blank.
     * Empty when the user has none — which is not an error here, but the
     * sentence a tool result has to carry.
     */
    public Optional<Connection> resolve(UserId userId, String provider, String key) {
        if (userId == null) return Optional.empty();
        List<Connection> candidates = of(userId, provider);
        if (key == null || key.isBlank()) {
            return candidates.stream().filter(Connection::isDefault).findFirst()
                    .or(() -> candidates.stream().findFirst());
        }
        return candidates.stream().filter(c -> c.key().equals(key.strip())).findFirst();
    }

    public Optional<Connection> find(UserId userId, ConnectionId id) {
        return connections.findById(id).filter(c -> c.userId().equals(userId));
    }

    // ── writing ─────────────────────────────────────────────────────────────

    /**
     * Attaches a new account.
     *
     * @param values       everything the form produced, secrets included
     * @param secretFields which of those names the provider's schema marked secret
     */
    public Connection add(UserId userId, String provider, String label,
                          Map<String, String> values, Set<String> secretFields) {
        Objects.requireNonNull(userId, "userId");
        synchronized (lockFor(userId)) {
            List<Connection> existing = of(userId, provider);
            Split split = Split.of(values, secretFields, Map.of());
            Connection created = Connection.of(userId, provider,
                    uniqueKey(existing, Connection.keyFrom(label)), label,
                    split.credentials(), split.settings(), existing.isEmpty(), clock.instant());
            connections.save(created);
            return created;
        }
    }

    /**
     * Attaches an account whose credentials somebody else produced — the
     * token an OAuth callback came back with. The same rules as {@link #add}:
     * a unique key, the first one becomes the default.
     *
     * @param label what to call it; the user can rename it afterwards, which
     *              is safe because the key is fixed here and never changes
     */
    public Connection attach(UserId userId, String provider, String label,
                             UserCredentials credentials, Map<String, String> settings) {
        Objects.requireNonNull(userId, "userId");
        synchronized (lockFor(userId)) {
            List<Connection> existing = of(userId, provider);
            Connection created = Connection.of(userId, provider,
                    uniqueKey(existing, Connection.keyFrom(label)), label,
                    credentials, settings, existing.isEmpty(), clock.instant());
            connections.save(created);
            return created;
        }
    }

    /**
     * Replaces just the credentials — a refreshed token, and nothing else.
     * Not {@link #update}: that is the edit form, and a refresh must not touch
     * a label or a setting somebody changed in between.
     */
    public Connection replaceCredentials(ConnectionId id, UserCredentials credentials) {
        Connection stored = connections.findById(id).orElseThrow(() ->
                new IllegalArgumentException("No connection " + id.value()));
        Connection refreshed = stored.withCredentials(credentials, clock.instant());
        connections.save(refreshed);
        return refreshed;
    }

    /** Replaces the values of an existing connection; a blank secret keeps the stored one. */
    public Optional<Connection> update(UserId userId, ConnectionId id, String label,
                                       Map<String, String> values, Set<String> secretFields) {
        Objects.requireNonNull(userId, "userId");
        synchronized (lockFor(userId)) {
            return find(userId, id).map(stored -> {
                Map<String, String> keep = stored.credentials() instanceof FormCreds form ? form.secrets() : Map.of();
                Split split = Split.of(values, secretFields, keep);
                Connection updated = stored
                        .withValues(split.credentials(), split.settings(), clock.instant())
                        .withLabel(label == null || label.isBlank() ? stored.label() : label, clock.instant());
                connections.save(updated);
                return updated;
            });
        }
    }

    /**
     * Gives an existing connection a new name and nothing else — the
     * credentials stay whatever they are, OAuth tokens included. Empty when
     * it is not theirs; a blank name changes nothing.
     */
    public Optional<Connection> rename(UserId userId, ConnectionId id, String label) {
        Objects.requireNonNull(userId, "userId");
        if (label == null || label.isBlank()) return find(userId, id);
        synchronized (lockFor(userId)) {
            return find(userId, id).map(stored -> {
                Connection renamed = stored.withLabel(label.strip(), clock.instant());
                connections.save(renamed);
                return renamed;
            });
        }
    }

    /** Makes {@code id} the user's default for its provider; false when it is not theirs. */
    public boolean setDefault(UserId userId, ConnectionId id) {
        synchronized (lockFor(userId)) {
            Connection target = find(userId, id).orElse(null);
            if (target == null) return false;
            Instant now = clock.instant();
            for (Connection other : of(userId, target.provider())) {
                if (other.isDefault() && !other.id().equals(id)) {
                    connections.save(other.withDefault(false, now));
                }
            }
            connections.save(target.withDefault(true, now));
            return true;
        }
    }

    /** Detaches it, promoting the next one when the default goes; false when it is not theirs. */
    public boolean remove(UserId userId, ConnectionId id) {
        synchronized (lockFor(userId)) {
            Connection target = find(userId, id).orElse(null);
            if (target == null) return false;
            connections.deleteById(id);
            if (target.isDefault()) {
                of(userId, target.provider()).stream().findFirst()
                        .ifPresent(next -> connections.save(next.withDefault(true, clock.instant())));
            }
            return true;
        }
    }

    /**
     * Records that a use was refused — a changed password, a revoked app — so
     * the list can say why instead of every call failing the same way.
     */
    public void markUnusable(ConnectionId id, ConnectionState state, String detail) {
        connections.findById(id).ifPresent(stored ->
                connections.save(stored.withState(state, detail, clock.instant())));
    }

    /** Puts a connection back in service — a corrected password, a refreshed token. */
    public void markUsable(ConnectionId id) {
        connections.findById(id).filter(c -> !c.usable()).ifPresent(stored ->
                connections.save(stored.withState(ConnectionState.CONNECTED, null, clock.instant())));
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** The split of one form into the readable half and the secret half. */
    private record Split(Map<String, String> settings, UserCredentials credentials) {

        static Split of(Map<String, String> values, Set<String> secretFields, Map<String, String> keep) {
            Map<String, String> settings = new LinkedHashMap<>();
            Map<String, String> secrets = new LinkedHashMap<>(keep);
            if (values != null) {
                values.forEach((name, value) -> {
                    if (secretFields != null && secretFields.contains(name)) {
                        // Blank means "leave it": the form never shows a stored secret back.
                        if (value != null && !value.isBlank()) secrets.put(name, value);
                    } else if (value != null && !value.isBlank()) {
                        settings.put(name, value);
                    }
                });
            }
            return new Split(settings, secrets.isEmpty() ? null : FormCreds.of(secrets));
        }
    }

    /** {@code arbeit}, then {@code arbeit-2} — never an existing key. */
    private static String uniqueKey(List<Connection> existing, String wanted) {
        Set<String> taken = existing.stream().map(Connection::key).collect(java.util.stream.Collectors.toSet());
        if (!taken.contains(wanted)) return wanted;
        for (int n = 2; ; n++) {
            String candidate = wanted + "-" + n;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    private static Comparator<Connection> defaultFirst() {
        return Comparator.comparing(Connection::isDefault).reversed()
                .thenComparing(Connection::label, String.CASE_INSENSITIVE_ORDER);
    }

    private Object lockFor(UserId id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }
}
