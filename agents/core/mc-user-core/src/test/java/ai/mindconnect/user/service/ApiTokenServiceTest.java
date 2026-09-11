package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A token is a secret shown once and stored only as its hash; it authenticates
 * its owner until it expires or the owner revokes it, and nobody else can
 * revoke it.
 */
class ApiTokenServiceTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private final MapRepositories.Tokens tokens = new MapRepositories.Tokens();
    private final MapRepositories.SettableClock clock =
            new MapRepositories.SettableClock(Instant.parse("2026-09-11T12:00:00Z"));
    private final ApiTokenService service = new ApiTokenService(tokens, clock);

    @Test
    void anIssuedSecretAuthenticatesItsOwnerAndIsStoredOnlyAsItsHash() {
        ApiTokenService.Issued issued = service.issue(ALICE, "  laptop ", null);

        assertThat(issued.secret()).startsWith(ApiTokenService.PREFIX).hasSizeGreaterThan(40);
        ApiToken stored = tokens.byId.get(issued.token().id());
        assertThat(stored.name()).isEqualTo("laptop");
        assertThat(stored.tokenHash()).isEqualTo(ApiTokenService.hash(issued.secret()))
                .doesNotContain(issued.secret());
        assertThat(issued.secret()).startsWith(stored.hint());
        assertThat(stored.hint()).hasSize(ApiTokenService.PREFIX.length() + 6);

        assertThat(service.authenticate(issued.secret())).map(ApiToken::userId).contains(ALICE);
    }

    @Test
    void twoTokensNeverShareASecret() {
        String first = service.issue(ALICE, "a", null).secret();
        String second = service.issue(ALICE, "b", null).secret();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void anythingButAnIssuedSecretAuthenticatesNobody() {
        String secret = service.issue(ALICE, "laptop", null).secret();

        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("")).isEmpty();
        assertThat(service.authenticate(ApiTokenService.PREFIX)).isEmpty();
        assertThat(service.authenticate("eyJhbGciOiJSUzI1NiJ9.a.b")).isEmpty();
        assertThat(service.authenticate(secret + "x")).isEmpty();
        assertThat(service.authenticate(secret.substring(0, secret.length() - 1))).isEmpty();
    }

    @Test
    void anExpiredTokenNoLongerAuthenticates() {
        String secret = service.issue(ALICE, "ci", clock.instant().plus(Duration.ofDays(30))).secret();

        clock.advance(Duration.ofDays(30).minusSeconds(1));
        assertThat(service.authenticate(secret)).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.authenticate(secret)).isEmpty();
    }

    @Test
    void onlyTheOwnerCanRevoke() {
        ApiTokenService.Issued issued = service.issue(ALICE, "laptop", null);

        assertThat(service.revoke(BOB, issued.token().id())).isFalse();
        assertThat(service.authenticate(issued.secret())).isPresent();

        assertThat(service.revoke(ALICE, issued.token().id())).isTrue();
        assertThat(service.authenticate(issued.secret())).isEmpty();
        assertThat(service.revoke(ALICE, issued.token().id())).isFalse();
    }

    @Test
    void theListShowsOnlyTheOwnersTokensNewestFirst() {
        service.issue(ALICE, "old", null);
        clock.advance(Duration.ofMinutes(1));
        service.issue(ALICE, "new", null);
        service.issue(BOB, "bobs", null);

        assertThat(service.list(ALICE)).extracting(ApiToken::name).containsExactly("new", "old");
    }

    @Test
    void useIsRecordedAtMostOncePerResolution() {
        String secret = service.issue(ALICE, "ci", null).secret();
        int afterIssue = tokens.writes;

        service.authenticate(secret);
        service.authenticate(secret);
        clock.advance(ApiTokenService.USE_RESOLUTION.minusSeconds(1));
        service.authenticate(secret);
        assertThat(tokens.writes).isEqualTo(afterIssue + 1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.authenticate(secret)).map(ApiToken::lastUsedAt).contains(clock.instant());
        assertThat(tokens.writes).isEqualTo(afterIssue + 2);
    }

    @Test
    void aNameIsRequiredAndTheExpiryMustLieAhead() {
        assertThatThrownBy(() -> service.issue(ALICE, " ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(ALICE, "x".repeat(ApiTokenService.MAX_NAME_LENGTH + 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issue(ALICE, "ci", clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tokens.byId).isEmpty();
    }
}
