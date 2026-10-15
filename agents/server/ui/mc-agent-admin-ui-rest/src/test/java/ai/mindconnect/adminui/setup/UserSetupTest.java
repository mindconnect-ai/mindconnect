package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolVariable;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.user.adapter.memory.InMemoryNotificationRepository;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationLevel;
import ai.mindconnect.user.service.NotificationService;
import ai.mindconnect.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a user finds when they sign in: the variables that could be filled in
 * for them are, and the ones that could not are waiting as notices.
 */
class UserSetupTest {

    private static final UserId ALICE = UserId.of("alice");

    private static final ToolVariable HOST =
            ToolVariable.required("MC_EMAIL_HOST", "Mail server", "Your IMAP server.");
    private static final ToolVariable PASSWORD =
            ToolVariable.secret("MC_EMAIL_PASSWORD", "Mailbox password", null);
    private static final ToolVariable PORT =
            ToolVariable.withDefault("MC_EMAIL_PORT", "Mail port", null, "993");
    private static final ToolVariable SMTP =
            ToolVariable.optional("MC_SMTP_HOST", "SMTP server", null);

    private final UserService users = new UserService(new InMemoryUserRepository());
    private final NotificationService notifications =
            new NotificationService(new InMemoryNotificationRepository());

    @Test
    void a_variable_with_a_default_is_created_rather_than_asked_about() {
        setup(declaring(PORT), EnvVarResolver.none()).run(ALICE);

        assertThat(users.environment(ALICE)).containsEntry("MC_EMAIL_PORT", "993");
        assertThat(notifications.open(ALICE)).isEmpty();
    }

    @Test
    void a_required_variable_nobody_has_becomes_one_notice_per_variable() {
        setup(declaring(HOST, PASSWORD), EnvVarResolver.none()).run(ALICE);

        assertThat(notifications.open(ALICE))
                .extracting(Notification::key)
                .containsExactlyInAnyOrder("setup.tool-variable.MC_EMAIL_HOST",
                        "setup.tool-variable.MC_EMAIL_PASSWORD");
        assertThat(notifications.open(ALICE)).allSatisfy(notification -> {
            assertThat(notification.level()).isEqualTo(NotificationLevel.ACTION_REQUIRED);
            assertThat(notification.actionHref()).isEqualTo("/admin/profile");
        });
    }

    @Test
    void an_optional_variable_is_an_offer_and_never_a_notice() {
        setup(declaring(SMTP), EnvVarResolver.none()).run(ALICE);

        assertThat(notifications.open(ALICE)).isEmpty();
        assertThat(users.environment(ALICE)).doesNotContainKey("MC_SMTP_HOST");
    }

    @Test
    void nothing_is_asked_for_what_the_namespace_or_the_server_already_answers() {
        // The operator put the value in the environment for everyone. Asking
        // every user for it anyway would be noise about a solved problem.
        setup(declaring(HOST, PORT), EnvVarResolver.of(Map.of("MC_EMAIL_HOST", "imap.example.com"))).run(ALICE);

        assertThat(notifications.open(ALICE)).isEmpty();
        assertThat(users.environment(ALICE)).doesNotContainKey("MC_EMAIL_HOST");
    }

    @Test
    void a_default_never_overwrites_a_value_the_user_chose() {
        users.putVariable(ALICE, "MC_EMAIL_PORT", "143");

        setup(declaring(PORT), EnvVarResolver.none()).run(ALICE);

        assertThat(users.environment(ALICE)).containsEntry("MC_EMAIL_PORT", "143");
    }

    @Test
    void the_notice_goes_away_by_itself_once_the_user_has_filled_the_value_in() {
        UserSetup setup = setup(declaring(HOST), EnvVarResolver.none());
        setup.run(ALICE);
        assertThat(notifications.open(ALICE)).hasSize(1);

        users.putVariable(ALICE, "MC_EMAIL_HOST", "imap.example.com");
        setup.run(ALICE);

        assertThat(notifications.open(ALICE)).isEmpty();
    }

    @Test
    void signing_in_again_with_the_same_gap_does_not_pile_up_notices() {
        UserSetup setup = setup(declaring(HOST), EnvVarResolver.none());

        setup.run(ALICE);
        int raisedAgain = setup.run(ALICE);

        assertThat(raisedAgain).isZero();
        assertThat(notifications.open(ALICE)).hasSize(1);
    }

    @Test
    void a_check_that_throws_does_not_keep_anybody_from_signing_in() {
        UserSetup setup = new UserSetup(provider(List.of(new Exploding())), provider(notifications));

        assertThat(setup.run(ALICE)).isZero();
    }

    @Test
    void a_host_without_notifications_does_the_checks_no_harm() {
        UserSetup setup = new UserSetup(provider(List.of(new ToolVariableSetupCheck(
                new ToolVariables(provider(declaring(HOST)), users, provider(EnvVarResolver.none()))))),
                provider((NotificationService) null));

        assertThat(setup.run(ALICE)).isZero();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private UserSetup setup(ToolRegistry registry, EnvVarResolver shared) {
        ToolVariables variables = new ToolVariables(provider(registry), users, provider(shared));
        return new UserSetup(provider(List.of(new ToolVariableSetupCheck(variables))), provider(notifications));
    }

    private static ToolRegistry declaring(ToolVariable... variables) {
        List<ToolVariable> declared = List.of(variables).stream().map(v -> v.declaredBy("email")).toList();
        return new ToolRegistry() {
            @Override public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
                return Optional.empty();
            }
            @Override public List<ToolVariable> declaredVariables() { return declared; }
        };
    }

    private static final class Exploding implements SetupCheck {
        @Override public String keyPrefix() { return "setup.exploding."; }
        @Override public List<Notification.Draft> run(UserId user) {
            throw new IllegalStateException("the mail server is down");
        }
    }

    /** The one ObjectProvider method these classes use; Spring supplies the real thing. */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return value; }
        };
    }

    private static <T> ObjectProvider<T> provider(List<T> values) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return values.isEmpty() ? null : values.get(0); }
            @Override public java.util.stream.Stream<T> orderedStream() { return values.stream(); }
        };
    }
}
