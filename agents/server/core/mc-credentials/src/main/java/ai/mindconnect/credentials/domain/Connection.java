package ai.mindconnect.credentials.domain;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolConnection;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One account a user has attached to this installation — their mailbox, their
 * calendar, their tracker.
 *
 * <p>The distinction that makes this worth its own entity: <b>what a tool can
 * do is the installation's business, whose account it does it with is the
 * user's.</b> A tool declares that it needs a connection of some
 * {@link #provider}; the user attaches one, or several, and the runtime hands
 * the right one to each call.
 *
 * <p>A user may hold several connections of one provider — "privat" and
 * "arbeit" — which is the whole reason this is not keyed
 * {@code (user, provider)} the way {@link ExternalIdentity} is.
 *
 * <h2>key vs. label</h2>
 * {@link #key()} is what a tool call and a pinned parameter name; it is
 * derived from the label once and then fixed. {@link #label()} is what a
 * person reads and may rename at will. Keeping them apart is the lesson of
 * stable tool identity: a rename must not break a stored reference.
 *
 * <h2>settings vs. credentials</h2>
 * The provider's {@code Schema} decides, not the tool and not the form: a
 * field marked {@code Format.PASSWORD} lands in {@link #credentials()} and is
 * encrypted at rest and never shown again; every other field lands in
 * {@link #settings()}, stays readable and can be corrected. {@link #value}
 * reads across both, so a tool never has to know which side a field is on.
 *
 * @param id          the connection's own id
 * @param userId      whose account it is. Installation-wide: a connection
 *                    follows the person, not the namespace they happen to work in.
 *                    A namespace-owned connection (a team mailbox) would add a
 *                    field here; a document store takes that additively, an
 *                    absent owner meaning this one
 * @param provider    what it connects to — {@code "email"}, {@code "microsoft"}.
 *                    Matches {@code ConnectionSpec.provider}, not the tool group
 * @param key         stable, lowercase: {@code "arbeit"}
 * @param label       what the user called it: {@code "Arbeit"}
 * @param state       whether it can still be used
 * @param stateDetail why it is not usable, for the list; null while connected
 * @param credentials the secret half; null only for a provider that has none
 * @param settings    the readable half — host, port, folder. Never null
 * @param isDefault   the one a call takes when it names none. Exactly one per
 *                    (user, provider), enforced by {@code ConnectionService}
 * @param createdAt   when it was attached
 * @param updatedAt   when it was last changed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Connection(
        ConnectionId id,
        UserId userId,
        String provider,
        String key,
        String label,
        ConnectionState state,
        String stateDetail,
        UserCredentials credentials,
        Map<String, String> settings,
        boolean isDefault,
        Instant createdAt,
        Instant updatedAt
) implements ToolConnection {

    /** What a key may look like: lowercase letters, digits, {@code -} and {@code _}. */
    public static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    public Connection {
        Objects.requireNonNull(id, "A connection needs an id");
        Objects.requireNonNull(userId, "A connection needs a user");
        requireText(provider, "provider");
        requireText(key, "key");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "'" + key + "' is not a connection key: lowercase letters, digits, '-' and '_'");
        }
        if (label == null || label.isBlank()) label = key;
        if (state == null) state = ConnectionState.CONNECTED;
        settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /** A freshly attached connection. */
    public static Connection of(UserId userId, String provider, String key, String label,
                                UserCredentials credentials, Map<String, String> settings,
                                boolean isDefault, Instant now) {
        return new Connection(ConnectionId.random(), userId, provider, key, label,
                ConnectionState.CONNECTED, null, credentials, settings, isDefault, now, now);
    }

    /**
     * The value of one field, wherever the schema put it: the readable
     * settings first, then the secrets. A tool asks for {@code "password"} and
     * gets it without knowing that it lives on the encrypted side.
     */
    @Override
    public String value(String field) {
        String plain = settings.get(field);
        if (plain != null) return plain;
        return credentials instanceof FormCreds form ? form.get(field) : null;
    }

    /** True while the connection is usable as far as anybody knows. */
    @Override
    public boolean usable() {
        return state == ConnectionState.CONNECTED;
    }

    public Connection withLabel(String newLabel, Instant now) {
        return new Connection(id, userId, provider, key, newLabel, state, stateDetail,
                credentials, settings, isDefault, createdAt, now);
    }

    public Connection withDefault(boolean nowDefault, Instant now) {
        return new Connection(id, userId, provider, key, label, state, stateDetail,
                credentials, settings, nowDefault, createdAt, now);
    }

    /** The same connection with new values — what saving the edit form produces. */
    public Connection withValues(UserCredentials newCredentials, Map<String, String> newSettings, Instant now) {
        return new Connection(id, userId, provider, key, label, ConnectionState.CONNECTED, null,
                newCredentials, newSettings, isDefault, createdAt, now);
    }

    /** The same connection marked unusable, with the reason a person can act on. */
    public Connection withState(ConnectionState newState, String detail, Instant now) {
        return new Connection(id, userId, provider, key, label, newState, detail,
                credentials, settings, isDefault, createdAt, now);
    }

    /** The same connection with exactly these credentials — token refresh. */
    public Connection withCredentials(UserCredentials newCredentials, Instant now) {
        return new Connection(id, userId, provider, key, label, state, stateDetail,
                newCredentials, settings, isDefault, createdAt, now);
    }

    /**
     * A key derived from what the user typed: lowercased, spaces and anything
     * else reduced to {@code -}. Blank input, or input that reduces to
     * nothing, falls back to {@code "default"} — a key is required and a form
     * should not refuse somebody for calling their mailbox "📮".
     */
    public static String keyFrom(String label) {
        if (label == null) return "default";
        String key = label.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return key.isEmpty() ? "default" : key;
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A connection needs a " + what);
        }
    }

    /** Never the credentials. */
    @Override
    public String toString() {
        return "Connection[" + provider + "/" + key + " of " + userId.value() + ", " + state + "]";
    }
}
