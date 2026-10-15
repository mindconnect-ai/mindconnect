package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.adapter.memory.InMemoryApiTokenRepository;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;
import ai.mindconnect.user.service.ApiTokenService;
import ai.mindconnect.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two kinds of bearer token: ours is checked against the token store and
 * never handed on; anything else goes to the JWT provider, where only a token
 * naming a user becomes one. Both record who signed in.
 */
class BearerAuthenticationTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final ApiTokenService tokens = new ApiTokenService(new InMemoryApiTokenRepository());
    private final UserRecorder recorder = new UserRecorder(new UserService(users));
    private final ApiTokenAuthenticationProvider provider = new ApiTokenAuthenticationProvider(tokens, recorder);

    @Test
    void anIssuedTokenAuthenticatesItsOwnerAndRecordsTheSignIn() {
        ApiTokenService.Issued issued = tokens.issue(UserId.of("alice"), "cli", null);

        Authentication result = provider.authenticate(new BearerTokenAuthenticationToken(issued.secret()));

        assertThat(result).isInstanceOfSatisfying(ApiTokenAuthentication.class, auth -> {
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getPrincipal()).isEqualTo(UserId.of("alice"));
            assertThat(auth.tokenId()).isEqualTo(issued.token().id());
            assertThat(auth.getCredentials()).isNull();
        });
        assertThat(users.findById(UserId.of("alice"))).isPresent();
    }

    @Test
    void aRevokedOrUnknownTokenOfOurShapeIsRejectedHere() {
        ApiTokenService.Issued issued = tokens.issue(UserId.of("alice"), "cli", null);
        tokens.revoke(UserId.of("alice"), issued.token().id());

        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken(issued.secret())))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> provider.authenticate(new BearerTokenAuthenticationToken("mct_forged")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void aTokenOfAnotherShapeIsLeftToTheNextProvider() {
        assertThat(provider.authenticate(new BearerTokenAuthenticationToken("eyJhbGciOiJSUzI1NiJ9.e30.sig"))).isNull();
        assertThat(provider.supports(BearerTokenAuthenticationToken.class)).isTrue();
    }

    @Test
    void aJwtBecomesTheUserItNamesAndIsRecordedWithItsDetails() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-1").issuer("https://auth.example.com/realms/mc")
                .claim("preferred_username", "bob").claim("name", "Bob Builder").claim("email", "bob@example.com")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();

        var authentication = new JwtUserConverter(recorder).convert(jwt);

        assertThat(authentication.getName()).isEqualTo("bob");
        assertThat(users.findById(UserId.of("bob"))).get()
                .extracting(User::subject, User::issuer, User::displayName, User::email)
                .containsExactly("sub-1", "https://auth.example.com/realms/mc", "Bob Builder", "bob@example.com");
    }

    @Test
    void aJwtWithoutTheUserClaimIsRejectedRatherThanMappedToAnotherIdentity() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertThatThrownBy(() -> new JwtUserConverter(recorder).convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(users.findAll()).isEmpty();
    }

    @Test
    void theRecorderCallsTheServiceOncePerResolutionAndSurvivesAFailingStore() {
        var counting = new CountingUsers(new InMemoryUserRepository(), false);
        var clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
        var throttled = new UserRecorder(new UserService(counting, clock), clock);

        throttled.record(UserId.of("alice"), null, null, "Alice", null);
        throttled.record(UserId.of("alice"), null, null, "Alice Smith", null);
        assertThat(counting.saves).isEqualTo(1);

        var broken = new UserRecorder(new UserService(new CountingUsers(new InMemoryUserRepository(), true)));
        broken.record(UserId.of("alice"), null, null, null, null);
    }

    @Test
    void aRejectedApiTokenIsNeverHandedToTheJwtCheck() {
        int[] decodes = {0};
        JwtDecoder failing = token -> {
            decodes[0]++;
            throw new IllegalStateException("the JWT decoder must not see this token");
        };
        var api = new ApiAuthentication(tokens, recorder, failing);

        assertThatThrownBy(() -> api.authenticationManager()
                .authenticate(new BearerTokenAuthenticationToken("mct_forged")))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(decodes[0]).isZero();
    }

    @Test
    void anUnreachableIssuerRejectsTheJwtInsteadOfFailingTheRequest() {
        JwtDecoder decoder = ApiAuthentication.jwtDecoder("http://127.0.0.1:1/realms/nowhere", List.of());
        var api = new ApiAuthentication(tokens, recorder, decoder);

        assertThatThrownBy(() -> api.authenticationManager()
                .authenticate(new BearerTokenAuthenticationToken("eyJhbGciOiJSUzI1NiJ9.e30.sig")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    /** Counts saves, or fails them. */
    private static class CountingUsers implements UserRepository {
        private final UserRepository delegate;
        private final boolean failOnSave;
        int saves;

        CountingUsers(UserRepository delegate, boolean failOnSave) {
            this.delegate = delegate;
            this.failOnSave = failOnSave;
        }

        @Override public Optional<User> findById(UserId id) { return delegate.findById(id); }
        @Override public List<User> findAll() { return delegate.findAll(); }
        @Override public void save(User user) {
            if (failOnSave) throw new IllegalStateException("disk full");
            saves++;
            delegate.save(user);
        }
    }
}
