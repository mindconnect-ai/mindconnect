package ai.mindconnect.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Authentication off: every request runs as one fixed user, presented exactly
 * like a browser login ({@link OidcUser}, {@code preferred_username} = the
 * user), so that nothing downstream needs a second code path for the dev
 * mode. Not a bean: an app adds it to its open filter chain only.
 */
public class DevUserFilter extends OncePerRequestFilter {

    private final OidcUser devUser;

    public DevUserFilter(String userName) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("dev")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(3650, ChronoUnit.DAYS))
                .subject(userName)
                .claim(StandardClaimNames.PREFERRED_USERNAME, userName)
                .claim(StandardClaimNames.NAME, userName)
                .build();
        this.devUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || !(current.getPrincipal() instanceof OidcUser)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    devUser, null, devUser.getAuthorities()));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
