package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

/**
 * The caller of the current request, from whatever authenticated it: a
 * personal API token, a bearer JWT, or a browser login (including the fixed
 * dev user, which is a login too). Everything else — anonymous, or an
 * authentication this does not know — has no caller, and the REST layer
 * answers 401.
 */
public class SecurityCurrentUserResolver implements CurrentUserResolver {

    @Override
    public Optional<UserId> currentUser() {
        return userIdOf(SecurityContextHolder.getContext().getAuthentication());
    }

    /** The user an authentication stands for; empty when it stands for nobody we know. */
    public static Optional<UserId> userIdOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication instanceof ApiTokenAuthentication token) {
            return Optional.of(token.getPrincipal());
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return nonBlank(jwt.getName());
        }
        if (authentication.getPrincipal() instanceof OidcUser oidc) {
            return nonBlank(oidc.getPreferredUsername());
        }
        return Optional.empty();
    }

    private static Optional<UserId> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(UserId.of(value));
    }
}
