package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * Records the user behind a browser login (or the dev user) — the one kind of
 * authentication that does not pass through a place of ours where it could be
 * recorded, the way a JWT or an API token does. Not a bean: an app adds it to
 * its filter chains, so it never runs outside them.
 *
 * <p>The address is recorded only when the provider does not say it is
 * unverified — see {@link VerifiedEmail}. The browser's time zone, which the
 * Admin UI's shell sends as a cookie, becomes the user's the first time it is
 * seen — see {@link UserRecorder#offerTimeZone}.
 */
public class UserRecordingFilter extends OncePerRequestFilter {

    private final UserRecorder recorder;

    public UserRecordingFilter(UserRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OidcUser oidc
                && oidc.getPreferredUsername() != null && !oidc.getPreferredUsername().isBlank()) {
            recorder.record(UserId.of(oidc.getPreferredUsername()), oidc.getSubject(),
                    oidc.getIssuer() == null ? null : oidc.getIssuer().toString(),
                    oidc.getFullName(), VerifiedEmail.of(oidc.getEmail(), oidc.getClaims()));
            recorder.offerTimeZone(UserId.of(oidc.getPreferredUsername()), browserTimeZone(request));
        }
        chain.doFilter(request, response);
    }

    /** What the Admin UI's shell wrote into {@value UserRecorder#TIME_ZONE_COOKIE}; null when it did not. */
    static String browserTimeZone(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (UserRecorder.TIME_ZONE_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value == null) return null;
                try {
                    // The shell URI-encodes it; a zone id is short and plain ASCII.
                    value = java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null;
                }
                return value.length() > 64 ? null : value;
            }
        }
        return null;
    }
}
