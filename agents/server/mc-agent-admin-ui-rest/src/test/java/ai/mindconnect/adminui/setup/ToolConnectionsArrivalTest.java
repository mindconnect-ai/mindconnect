package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ConnectionTest;
import ai.mindconnect.agent.tool.ConnectionTester;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.credentials.adapter.memory.InMemoryConnectionRepository;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionState;
import ai.mindconnect.credentials.service.ConnectionService;
import ai.mindconnect.schema.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A connection that just arrived through a sign-in: tried out, and named after whose it is. */
class ToolConnectionsArrivalTest {

    private static final ConnectionSpec MICROSOFT = ConnectionSpec.form("microsoft", "Microsoft account", Schema.object());

    private final ConnectionService service = new ConnectionService(new InMemoryConnectionRepository());
    private final UserId david = UserId.of("david");

    @Test
    void a_test_that_names_the_account_renames_the_connection_and_its_verdict_is_handed_back() {
        ToolConnections connections = connections(
                c -> ConnectionTest.ok("Signed in to Microsoft as David <david@example.com>.", "david@example.com"));
        Connection created = service.add(david, "microsoft", "Microsoft account", Map.of(), Set.of());

        ToolConnections.Arrival arrival = connections.arrived(created);

        assertThat(arrival.connection().label()).isEqualTo("david@example.com");
        assertThat(arrival.connection().key()).isEqualTo(created.key());
        assertThat(arrival.test()).map(ConnectionTest::message).contains("Signed in to Microsoft as David <david@example.com>.");
        assertThat(service.find(david, created.id()).orElseThrow().label()).isEqualTo("david@example.com");
    }

    @Test
    void a_source_that_does_not_say_leaves_the_cards_name() {
        ToolConnections connections = connections(c -> ConnectionTest.ok("Signed in."));
        Connection created = service.add(david, "microsoft", "Microsoft account", Map.of(), Set.of());

        assertThat(connections.arrived(created).connection().label()).isEqualTo("Microsoft account");
    }

    @Test
    void a_failed_test_keeps_the_name_and_marks_the_connection() {
        ToolConnections connections = connections(c -> ConnectionTest.failed("The token was refused."));
        Connection created = service.add(david, "microsoft", "Microsoft account", Map.of(), Set.of());

        ToolConnections.Arrival arrival = connections.arrived(created);

        assertThat(arrival.connection().label()).isEqualTo("Microsoft account");
        Connection stored = service.find(david, created.id()).orElseThrow();
        assertThat(stored.state()).isEqualTo(ConnectionState.ERROR);
        assertThat(stored.stateDetail()).isEqualTo("The token was refused.");
    }

    @Test
    void a_source_without_a_test_hands_the_connection_back_untouched() {
        ToolConnections connections = connections(null);
        Connection created = service.add(david, "microsoft", "Microsoft account", Map.of(), Set.of());

        ToolConnections.Arrival arrival = connections.arrived(created);

        assertThat(arrival.connection()).isEqualTo(created);
        assertThat(arrival.test()).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ToolConnections connections(ConnectionTester tester) {
        ToolRegistry registry = new ToolRegistry() {
            @Override public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) { return Optional.empty(); }
            @Override public List<ConnectionSpec> connectionSpecs() { return List.of(MICROSOFT); }
            @Override public Optional<ConnectionTester> connectionTesterOf(String provider) {
                return "microsoft".equals(provider) ? Optional.ofNullable(tester) : Optional.empty();
            }
        };
        return new ToolConnections(provider(registry), provider(service));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return value; }
        };
    }
}
