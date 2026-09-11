package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A login creates the user on first sight and keeps the details current, but
 * a stream of requests from the same user does not write on every one.
 */
class UserServiceTest {

    private static final UserId ALICE = UserId.of("alice");

    private final MapRepositories.Users users = new MapRepositories.Users();
    private final MapRepositories.SettableClock clock =
            new MapRepositories.SettableClock(Instant.parse("2026-09-11T12:00:00Z"));
    private final UserService service = new UserService(users, clock);

    @Test
    void theFirstLoginCreatesTheUser() {
        User user = service.recordLogin(ALICE, "sub-1", "https://auth/realms/mc", "Alice", "alice@example.com");

        assertThat(user.createdAt()).isEqualTo(clock.instant());
        assertThat(user.lastLoginAt()).isEqualTo(clock.instant());
        assertThat(service.find(ALICE)).contains(user);
    }

    @Test
    void repeatedRequestsWithinTheResolutionDoNotWrite() {
        service.recordLogin(ALICE, "sub-1", "iss", "Alice", "alice@example.com");
        clock.advance(UserService.LOGIN_RESOLUTION.minusSeconds(1));
        service.recordLogin(ALICE, "sub-1", "iss", "Alice", "alice@example.com");

        assertThat(users.saves).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        User later = service.recordLogin(ALICE, "sub-1", "iss", "Alice", "alice@example.com");
        assertThat(users.saves).isEqualTo(2);
        assertThat(later.lastLoginAt()).isEqualTo(clock.instant());
    }

    @Test
    void aChangedDetailIsWrittenAtOnceAndAMissingOneKeepsTheStoredValue() {
        Instant first = clock.instant();
        service.recordLogin(ALICE, "sub-1", "iss", "Alice", "alice@example.com");
        clock.advance(Duration.ofSeconds(10));

        User renamed = service.recordLogin(ALICE, null, null, "Alice Smith", null);

        assertThat(users.saves).isEqualTo(2);
        assertThat(renamed.displayName()).isEqualTo("Alice Smith");
        assertThat(renamed.email()).isEqualTo("alice@example.com");
        assertThat(renamed.subject()).isEqualTo("sub-1");
        assertThat(renamed.createdAt()).isEqualTo(first);
    }

    @Test
    void theLabelFallsBackToTheId() {
        assertThat(service.recordLogin(ALICE, null, null, null, null).label()).isEqualTo("alice");
    }
}
