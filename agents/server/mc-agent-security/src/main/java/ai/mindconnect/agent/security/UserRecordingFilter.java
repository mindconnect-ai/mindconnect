package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
                    oidc.getFullName(), oidc.getEmail());
        }
        chain.doFilter(request, response);
    }
}
