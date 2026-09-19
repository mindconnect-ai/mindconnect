package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model is offered, and what reaches the tool — the two halves of
 * putting a tool on the caller's own account.
 */
class ConnectionBoundToolTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final ToolCallScope SCOPE = ToolCallScope.detached(ALICE);

    private static final ConnectionSpec MAIL = ConnectionSpec
            .form("email", "Mailbox", Schema.object().prop("host", Schema.string()))
            .allowingSeveral();

    // ── what the model sees ─────────────────────────────────────────────────

    @Test
    void one_connection_is_no_choice_so_the_parameter_is_left_out() {
        Tool tool = bind(new RecordingTool(), MAIL, connections(connection("privat", true)));

        assertThat(properties(tool)).containsOnlyKeys("folder");
    }

    @Test
    void two_connections_become_an_enum_of_exactly_this_users_keys() {
        Tool tool = bind(new RecordingTool(), MAIL,
                connections(connection("privat", true), connection("arbeit", false)));

        Map<String, Object> account = property(tool, "account");
        assertThat(account).containsEntry("type", "string");
        assertThat(account.get("enum")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("privat", "arbeit");
        assertThat(String.valueOf(account.get("description"))).contains("privat").contains("arbeit");
    }

    @Test
    void a_user_without_a_connection_is_offered_nothing_to_choose() {
        Tool tool = bind(new RecordingTool(), MAIL, connections());

        assertThat(properties(tool)).containsOnlyKeys("folder");
    }

    @Test
    void a_tool_with_two_ends_always_asks_which_is_which() {
        // Even with one calendar: "from" and "to" is a distinction no default can make.
        ConnectionSpec copy = MAIL.params(ConnectionParam.of("from", "email"), ConnectionParam.of("to", "email"));
        Tool tool = bind(new RecordingTool(), copy, connections(connection("privat", true)));

        assertThat(properties(tool)).containsOnlyKeys("folder", "from", "to");
        assertThat(tool.parametersSchema().get("required"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).contains("from", "to");
    }

    // ── what reaches the tool ───────────────────────────────────────────────

    @Test
    void the_default_is_used_when_the_call_names_none() {
        RecordingTool recording = new RecordingTool();
        Tool tool = bind(recording, MAIL, connections(connection("privat", true), connection("arbeit", false)));

        assertThat(tool.execute(Map.of("folder", "INBOX"))).isEqualTo("ran on privat");
        assertThat(recording.arguments).containsExactly(Map.entry("folder", "INBOX"));
    }

    @Test
    void the_named_connection_wins_and_never_reaches_the_tool_as_an_argument() {
        RecordingTool recording = new RecordingTool();
        Tool tool = bind(recording, MAIL, connections(connection("privat", true), connection("arbeit", false)));

        assertThat(tool.execute(Map.of("account", "arbeit", "folder", "INBOX"))).isEqualTo("ran on arbeit");
        assertThat(recording.arguments).doesNotContainKey("account");
    }

    @Test
    void a_user_with_nothing_connected_is_told_where_to_connect_and_the_tool_is_never_entered() {
        RecordingTool recording = new RecordingTool();
        Tool tool = bind(recording, MAIL, connections());

        String result = tool.execute(Map.of("folder", "INBOX"));

        assertThat(result).startsWith("Error: ").contains("Mailbox").contains("Connections");
        assertThat(recording.arguments).isNull();
    }

    @Test
    void a_key_the_user_does_not_have_names_the_ones_they_do() {
        Tool tool = bind(new RecordingTool(), MAIL, connections(connection("privat", true)));

        assertThat(tool.execute(Map.of("account", "arbeit")))
                .startsWith("Error: ").contains("\"arbeit\"").contains("privat");
    }

    @Test
    void a_connection_that_is_not_usable_is_refused_before_the_tool_runs() {
        RecordingTool recording = new RecordingTool();
        Tool tool = bind(recording, MAIL, connections(new FakeConnection("privat", "Privat", true, false)));

        assertThat(tool.execute(Map.of())).startsWith("Error: ").contains("Privat").contains("again");
        assertThat(recording.arguments).isNull();
    }

    // ── the chain ───────────────────────────────────────────────────────────

    @Test
    void an_agent_can_bind_the_same_tool_twice_by_pinning_the_account() {
        // The mechanism that already exists (AliasTool + PinnedParamsTool),
        // now pointing at a connection: two names, two mailboxes, one tool.
        Connections available = connections(connection("privat", true), connection("arbeit", false));
        RecordingTool recording = new RecordingTool();

        AgentTool binding = AgentTool.of("email_arbeit_list", null,
                Map.of("tool", "email_list", "params", Map.of("account", "arbeit")));
        Tool tool = SpiToolRegistry.decorate(binding,
                ConnectionBoundTool.wrap(recording, MAIL, available, SCOPE));

        assertThat(tool.name()).isEqualTo("email_arbeit_list");
        assertThat(tool.parametersSchema()).extracting("properties")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey("account");          // pinned: not offered to the model
        assertThat(tool.execute(Map.of("folder", "INBOX"))).isEqualTo("ran on arbeit");
    }

    @Test
    void a_tool_that_needs_no_account_is_not_wrapped_at_all() {
        Tool plain = new PlainTool();

        assertThat(ConnectionBoundTool.wrap(plain, MAIL, connections(), SCOPE)).isSameAs(plain);
        assertThat(ConnectionBoundTool.wrap(new RecordingTool(), null, connections(), SCOPE))
                .isInstanceOf(RecordingTool.class);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Tool bind(Tool tool, ConnectionSpec spec, Connections available) {
        return ConnectionBoundTool.wrap(tool, spec, available, SCOPE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Tool tool) {
        return (Map<String, Object>) tool.parametersSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Tool tool, String name) {
        return (Map<String, Object>) properties(tool).get(name);
    }

    private static Connections connections(ToolConnection... available) {
        List<ToolConnection> all = List.of(available);
        return new Connections() {
            @Override public List<ToolConnection> of(UserId userId, String provider) { return all; }
            @Override public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
                return key == null || key.isBlank()
                        ? all.stream().findFirst()
                        : all.stream().filter(c -> c.key().equals(key)).findFirst();
            }
        };
    }

    private static ToolConnection connection(String key, boolean first) {
        return new FakeConnection(key, key.substring(0, 1).toUpperCase() + key.substring(1), first, true);
    }

    private record FakeConnection(String key, String label, boolean isDefault, boolean usable)
            implements ToolConnection {
        @Override public String provider() { return "email"; }
        @Override public String value(String field) { return null; }
    }

    /** Remembers what it was handed, so a test can prove what did not reach it. */
    private static final class RecordingTool implements ConnectedTool {
        Map<String, Object> arguments;

        @Override public String name() { return "email_list"; }
        @Override public String description() { return "Lists messages."; }
        @Override public Map<String, Object> parametersSchema() {
            return Map.of("type", "object", "properties", Map.of("folder", Map.of("type", "string")));
        }
        @Override public String execute(Map<String, Object> args, BoundConnections bound) {
            this.arguments = args;
            return "ran on " + bound.one().key();
        }
    }

    private static final class PlainTool implements Tool {
        @Override public String name() { return "web_search"; }
        @Override public String description() { return "Searches."; }
        @Override public Map<String, Object> parametersSchema() { return Map.of("type", "object"); }
        @Override public String execute(Map<String, Object> arguments) { return "ok"; }
    }
}
