package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.credentials.adapter.memory.InMemoryConnectionRepository;
import ai.mindconnect.credentials.service.ConnectionService;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.user.adapter.memory.InMemoryNotificationRepository;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationLevel;
import ai.mindconnect.user.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSetupCheckTest {

    private static final UserId ALICE = UserId.of("alice");

    private static final Schema MAILBOX = Schema.object()
            .prop("host", Schema.string().description("Your IMAP server."))
            .prop("password", Schema.string().format(Schema.Format.PASSWORD))
            .require("host", "password");

    private static final ConnectionSpec EMAIL = ConnectionSpec.form("email", "Mailbox", MAILBOX)
            .description("The mailbox the email tools read.")
            .allowingSeveral();

    private final ConnectionService service = new ConnectionService(new InMemoryConnectionRepository());
    private final NotificationService notifications =
            new NotificationService(new InMemoryNotificationRepository());

    @Test
    void a_user_with_nothing_attached_is_asked_once_per_provider() {
        setup(EMAIL).run(ALICE);

        assertThat(notifications.open(ALICE)).singleElement().satisfies(notice -> {
            assertThat(notice.key()).isEqualTo("setup.connection.email");
            assertThat(notice.title()).contains("Mailbox");
            assertThat(notice.level()).isEqualTo(NotificationLevel.ACTION_REQUIRED);
            assertThat(notice.actionHref()).isEqualTo("/admin/profile");
        });
    }

    @Test
    void the_notice_goes_away_by_itself_once_something_is_attached() {
        UserSetup setup = setup(EMAIL);
        setup.run(ALICE);
        assertThat(notifications.open(ALICE)).hasSize(1);

        service.add(ALICE, "email", "Privat", Map.of("host", "imap.example.com", "password", "s3cret"),
                Set.of("password"));
        setup.run(ALICE);

        assertThat(notifications.open(ALICE)).isEmpty();
    }

    @Test
    void signing_in_again_does_not_pile_notices_up() {
        UserSetup setup = setup(EMAIL);

        setup.run(ALICE);
        assertThat(setup.run(ALICE)).isZero();

        assertThat(notifications.open(ALICE)).hasSize(1);
    }

    @Test
    void a_host_that_keeps_no_connections_asks_for_none() {
        // Nowhere to attach one: a notice would be a request nobody can fulfil.
        ToolConnections without = new ToolConnections(provider(registry(EMAIL)), provider((ConnectionService) null));
        UserSetup setup = new UserSetup(provider(List.of(new ConnectionSetupCheck(without))),
                provider(notifications));

        assertThat(setup.run(ALICE)).isZero();
        assertThat(notifications.open(ALICE)).isEmpty();
    }

    @Test
    void the_schema_says_which_fields_are_secret() {
        assertThat(ToolConnections.secretFields(MAILBOX)).containsExactly("password");
        assertThat(ToolConnections.secretFields(null)).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private UserSetup setup(ConnectionSpec... specs) {
        ToolConnections connections = new ToolConnections(provider(registry(specs)), provider(service));
        return new UserSetup(provider(List.of(new ConnectionSetupCheck(connections))), provider(notifications));
    }

    private static ToolRegistry registry(ConnectionSpec... specs) {
        List<ConnectionSpec> declared = List.of(specs);
        return new ToolRegistry() {
            @Override public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
                return Optional.empty();
            }
            @Override public List<ConnectionSpec> connectionSpecs() { return declared; }
        };
    }

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
