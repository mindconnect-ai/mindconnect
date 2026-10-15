package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Namespaces list people by address, so an address somebody typed into their
 * own account at the provider must not make them somebody else. The attack
 * these tests stand for: a user changes their address at Keycloak to an
 * admin's, unverified, and signs in again.
 */
class VerifiedEmailTest {

    private static final UserId BOB = UserId.of("bob");

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final UserService service = new UserService(users);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anUnverifiedAddressInAJwtDoesNotReplaceTheVerifiedOneOnRecord() {
        signInWithJwt("bob@example.com", true);
        signInWithJwt("admin@example.com", false);

        assertThat(storedEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void anUnverifiedAddressIsNotRecordedForSomebodyWithoutOneEither() {
        signInWithJwt("admin@example.com", false);

        assertThat(storedEmail()).as("the namespace service then falls back to the id at the domain").isNull();
    }

    @Test
    void aVerifiedChangeOfAddressIsTaken() {
        signInWithJwt("bob@example.com", true);
        signInWithJwt("robert@example.com", true);

        assertThat(storedEmail()).isEqualTo("robert@example.com");
    }

    @Test
    void aProviderThatSendsNoClaimIsTakenAtItsWord() {
        signInWithJwt("bob@example.com", null);

        assertThat(storedEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void aBrowserLoginWithAnUnverifiedAddressKeepsTheVerifiedOneOnRecord() throws Exception {
        signInWithBrowser("bob@example.com", true);
        signInWithBrowser("admin@example.com", false);

        assertThat(storedEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void theClaimIsReadAsABooleanOrAsItsText() {
        assertThat(VerifiedEmail.of("a@b.c", Map.of(VerifiedEmail.CLAIM, "true"))).isEqualTo("a@b.c");
        assertThat(VerifiedEmail.of("a@b.c", Map.of(VerifiedEmail.CLAIM, "false"))).isNull();
        assertThat(VerifiedEmail.of("a@b.c", Map.of(VerifiedEmail.CLAIM, Boolean.FALSE))).isNull();
        assertThat(VerifiedEmail.of("a@b.c", null)).isEqualTo("a@b.c");
        assertThat(VerifiedEmail.of(" ", Map.of())).isNull();
    }

    private String storedEmail() {
        return users.findById(BOB).map(User::email).orElse(null);
    }

    /** A fresh recorder each time: the real one records a user at most once a while. */
    private UserRecorder recorder() {
        return new UserRecorder(service);
    }

    private void signInWithJwt(String email, Boolean verified) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-bob").issuer("https://auth.example.com/realms/mc")
                .claim("preferred_username", "bob").claim("email", email)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (verified != null) jwt.claim(VerifiedEmail.CLAIM, verified);
        new JwtUserConverter(recorder()).convert(jwt.build());
    }

    private void signInWithBrowser(String email, boolean verified) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "sub-bob");
        claims.put("preferred_username", "bob");
        claims.put("email", email);
        claims.put(VerifiedEmail.CLAIM, verified);
        OidcIdToken token = new OidcIdToken("id", Instant.now(), Instant.now().plusSeconds(60), claims);
        DefaultOidcUser principal = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
        FilterChain chain = (request, response) -> { };
        new UserRecordingFilter(recorder()).doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
    }
}
