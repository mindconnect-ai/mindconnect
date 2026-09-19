package ai.mindconnect.adminui.setup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What has to survive between sending a browser to the provider and its coming
 * back — kept on that browser's session and nowhere else.
 *
 * <p>Not in a store: it belongs to one browser for one minute, and a store
 * would have to be cleaned up, shared between nodes, and would turn a
 * short-lived secret into a persisted one. The session is exactly the right
 * lifetime.
 *
 * <p><b>The state is what makes the callback trustworthy.</b> A callback that
 * does not echo the value this session generated is somebody else's — or
 * somebody's attempt to attach their account to this user — and is refused.
 *
 * @param providerName      the app registration the attempt was started with
 * @param connectionProvider what the resulting connection is stored under
 * @param state             the one-time value the callback must echo
 * @param codeVerifier      the PKCE secret; null when the provider uses none
 * @param redirectUri       the exact URI the provider was told, which the
 *                          exchange has to repeat
 * @param startedAt         when it began, so a forgotten attempt expires
 */
public record PendingAuthorization(
        String providerName,
        String connectionProvider,
        String state,
        String codeVerifier,
        String redirectUri,
        Instant startedAt
) implements Serializable {

    /** How long an attempt may lie around before the callback is too late to trust. */
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    static final String SESSION_KEY = "mindconnect.oauth.pending";

    public PendingAuthorization {
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(state, "state");
    }

    /** Remembers this attempt on the request's session, replacing any earlier one. */
    public void rememberOn(HttpServletRequest request) {
        request.getSession(true).setAttribute(SESSION_KEY, this);
    }

    /**
     * The attempt this callback belongs to — taken off the session, so a code
     * cannot be replayed against it, and only when the state matches.
     */
    public static PendingAuthorization claim(HttpServletRequest request, String state, Instant now) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object stored = session.getAttribute(SESSION_KEY);
        session.removeAttribute(SESSION_KEY);
        if (!(stored instanceof PendingAuthorization pending)) {
            return null;
        }
        boolean matches = state != null && java.security.MessageDigest.isEqual(
                pending.state().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                state.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean fresh = pending.startedAt() != null
                && pending.startedAt().plus(LIFETIME).isAfter(now);
        return matches && fresh ? pending : null;
    }
}
