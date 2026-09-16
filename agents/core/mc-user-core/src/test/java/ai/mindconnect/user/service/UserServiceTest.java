package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void aLoginKeepsTheUsersVariables_andSetEnvironmentReplacesThem() {
        service.recordLogin(ALICE, "sub-1", "iss", "Alice", "alice@example.com");
        service.setEnvironment(ALICE, Map.of("OPENAI_API_KEY", "sk-alice"));
        clock.advance(UserService.LOGIN_RESOLUTION);

        User afterLogin = service.recordLogin(ALICE, "sub-1", "iss", "Alice B.", "alice@example.com");

        assertThat(afterLogin.environment()).containsEntry("OPENAI_API_KEY", "sk-alice");
        assertThat(service.environment(ALICE)).containsEntry("OPENAI_API_KEY", "sk-alice");

        service.setEnvironment(ALICE, Map.of());
        assertThat(service.environment(ALICE)).isEmpty();
        assertThat(service.environment(UserId.of("nobody"))).isEmpty();
    }

    @Test
    void setEnvironmentCreatesTheRecordOfAnUnseenUser_andRefusesABadName() {
        service.setEnvironment(UserId.of("carol"), Map.of("KEY", "v"));

        assertThat(service.find(UserId.of("carol"))).get().extracting(User::environment).isEqualTo(Map.of("KEY", "v"));
        assertThatThrownBy(() -> service.setEnvironment(ALICE, Map.of("bad name", "v")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bad name");

        assertThat(service.putVariable(ALICE, "A", "1").environment()).isEqualTo(Map.of("A", "1"));
        assertThat(service.putVariable(ALICE, "B", "2").environment()).isEqualTo(Map.of("A", "1", "B", "2"));
        assertThat(service.putVariable(ALICE, "A", "3").environment()).containsEntry("A", "3");
        assertThat(service.removeVariable(ALICE, "A")).isTrue();
        assertThat(service.removeVariable(ALICE, "A")).isFalse();
        assertThat(service.environment(ALICE)).isEqualTo(Map.of("B", "2"));
        assertThatThrownBy(() -> service.putVariable(ALICE, "C", " ")).hasMessageContaining("C");
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

    @Test
    void theChosenNamespaceIsRememberedAndSurvivesTheNextLogin() {
        service.recordLogin(ALICE, "sub-1", "iss", "Alice", "alice@example.com");

        assertThat(service.activeNamespace(ALICE)).isEmpty();
        service.selectNamespace(ALICE, new ai.mindconnect.agent.Namespace("acme"));
        assertThat(service.activeNamespace(ALICE)).contains(new ai.mindconnect.agent.Namespace("acme"));

        clock.advance(UserService.LOGIN_RESOLUTION.plusSeconds(1));
        service.recordLogin(ALICE, "sub-1", "iss", "Alice Smith", "alice@example.com");
        assertThat(service.activeNamespace(ALICE)).contains(new ai.mindconnect.agent.Namespace("acme"));
        assertThat(service.find(ALICE)).map(User::displayName).contains("Alice Smith");
    }

    @Test
    void choosingAgainWithTheSameNamespaceDoesNotWrite() {
        service.recordLogin(ALICE, "sub-1", "iss", "Alice", "alice@example.com");
        service.selectNamespace(ALICE, new ai.mindconnect.agent.Namespace("acme"));
        int saves = users.saves;

        service.selectNamespace(ALICE, new ai.mindconnect.agent.Namespace("acme"));

        assertThat(users.saves).isEqualTo(saves);
    }

    @Test
    void anUnknownUserGetsARecordForTheirChoice() {
        service.selectNamespace(UserId.of("bob"), new ai.mindconnect.agent.Namespace("acme"));

        assertThat(service.activeNamespace(UserId.of("bob"))).contains(new ai.mindconnect.agent.Namespace("acme"));
    }
}
