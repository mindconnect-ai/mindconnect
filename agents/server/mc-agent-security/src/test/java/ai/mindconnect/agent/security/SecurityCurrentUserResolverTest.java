package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiTokenId;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Every way a request can be authenticated maps to the same kind of caller — and nothing else maps to one. */
class SecurityCurrentUserResolverTest {

    @Test
    void anApiTokenStandsForItsOwner() {
        assertThat(SecurityCurrentUserResolver.userIdOf(new ApiTokenAuthentication(UserId.of("alice"), ApiTokenId.random())))
                .contains(UserId.of("alice"));
    }

    @Test
    void aJwtStandsForTheUserItNames() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1")
                .claim("preferred_username", "bob").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
        var authentication = new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER"), "bob");

        assertThat(SecurityCurrentUserResolver.userIdOf(authentication)).contains(UserId.of("bob"));
    }

    @Test
    void aBrowserLoginStandsForItsPreferredUsername() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id").subject("sub-2")
                .claim(StandardClaimNames.PREFERRED_USERNAME, "carol")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);

        assertThat(SecurityCurrentUserResolver.userIdOf(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities())))
                .contains(UserId.of("carol"));
    }

    @Test
    void anonymousUnauthenticatedAndUnknownAuthenticationsStandForNobody() {
        assertThat(SecurityCurrentUserResolver.userIdOf(null)).isEmpty();
        assertThat(SecurityCurrentUserResolver.userIdOf(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")))).isEmpty();
        assertThat(SecurityCurrentUserResolver.userIdOf(
                UsernamePasswordAuthenticationToken.unauthenticated("mallory", "guess"))).isEmpty();
        assertThat(SecurityCurrentUserResolver.userIdOf(
                new TestingAuthenticationToken("mallory", null, "ROLE_USER"))).isEmpty();
    }
}
