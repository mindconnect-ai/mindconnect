package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.mcp.gateway.McpCaller;
import ai.mindconnect.mcp.gateway.McpGatewayException;
import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.mcp.gateway.McpTool;
import ai.mindconnect.mcp.proxy.McpConnection;
import ai.mindconnect.mcp.proxy.McpEndpoint;
import ai.mindconnect.mcp.proxy.McpProxy;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A changed registration must not keep serving the old image, URL or token
 * from a pooled connection — and must not take the other servers' connections
 * down with it.
 */
class LocalMcpGatewayForgetTest {

    private static final McpServerId GMAIL = McpServerId.of("gmail");
    private static final McpServerId FILES = McpServerId.of("files");

    @TempDir
    Path storage;

    private final FakeProxy proxy = new FakeProxy();
    private final McpSessionRegistry sessions =
            new McpSessionRegistry(proxy, Duration.ofMinutes(30), Duration.ofHours(1));

    @AfterEach
    void shutDown() {
        sessions.shutdown();
    }

    private LocalMcpGateway gateway(InMemoryRepository repository) {
        return new LocalMcpGateway(repository, proxy, sessions, storage, Namespace.DEFAULT, "nothing-here");
    }

    @Test
    void forgetting_a_server_closes_its_connections_and_only_its() {
        LocalMcpGateway gateway = gateway(new InMemoryRepository().with(GMAIL, true).with(FILES, true));
        McpCaller caller = new McpCaller(UserId.of("alice"), SessionId.random());

        gateway.call(caller, GMAIL, "search_emails", Map.of());
        gateway.call(caller, FILES, "read_file", Map.of());
        assertThat(proxy.opened).hasSize(2);

        gateway.forget(GMAIL);

        assertThat(proxy.closed()).as("connections dropped").isEqualTo(1);
        gateway.call(caller, FILES, "read_file", Map.of());
        assertThat(proxy.opened).as("files still pooled").hasSize(2);
        gateway.call(caller, GMAIL, "search_emails", Map.of());
        assertThat(proxy.opened).as("gmail reconnects").hasSize(3);
    }

    @Test
    void a_disabled_or_unknown_server_is_refused_before_anything_starts() {
        LocalMcpGateway gateway = gateway(new InMemoryRepository().with(GMAIL, false));
        McpCaller caller = new McpCaller(UserId.of("alice"), SessionId.random());

        assertThatThrownBy(() -> gateway.call(caller, GMAIL, "search_emails", Map.of()))
                .isInstanceOf(McpGatewayException.class)
                .hasMessageContaining("gmail");
        assertThatThrownBy(() -> gateway.call(caller, FILES, "read_file", Map.of()))
                .isInstanceOf(McpGatewayException.class);
        assertThat(proxy.opened).isEmpty();
    }

    /** Registrations in memory; the version moves with every write. */
    private static final class InMemoryRepository implements McpServerRepository {

        private final Map<McpServerId, McpServerRegistration> registrations = new LinkedHashMap<>();
        private long version;

        InMemoryRepository with(McpServerId id, boolean enabled) {
            save(new McpServerRegistration(id, null, null, enabled, id.value(),
                    new McpTarget.Process(List.of("/bin/echo", id.value()), Map.of()), null));
            return this;
        }

        @Override public Optional<McpServerRegistration> findById(McpServerId id) {
            return Optional.ofNullable(registrations.get(id));
        }

        @Override public List<McpServerRegistration> findAll() {
            return List.copyOf(registrations.values());
        }

        @Override public Optional<McpServerRegistration> findByName(String name) {
            return registrations.values().stream()
                    .filter(r -> r.displayName().equalsIgnoreCase(name)).findFirst();
        }

        @Override public void save(McpServerRegistration registration) {
            registrations.put(registration.id(), registration);
            version++;
        }

        @Override public void deleteById(McpServerId id) {
            registrations.remove(id);
            version++;
        }

        @Override public long version() { return version; }
    }

    /** Hands out connections without starting anything, and counts them. */
    private static final class FakeProxy implements McpProxy {

        final List<FakeConnection> opened = new ArrayList<>();

        int closed() {
            return (int) opened.stream().filter(c -> c.closed).count();
        }

        @Override public McpConnection connect(McpEndpoint endpoint) {
            FakeConnection connection = new FakeConnection();
            opened.add(connection);
            return connection;
        }

        @Override public List<McpTool> listTools(McpEndpoint endpoint) { return List.of(); }

        @Override public McpResult callTool(McpEndpoint endpoint, String toolName, Map<String, Object> args) {
            return new McpResult(false, List.of("ok"));
        }
    }

    private static final class FakeConnection implements McpConnection {
        boolean closed;
        @Override public List<McpTool> listTools() { return List.of(); }
        @Override public McpResult callTool(String toolName, Map<String, Object> args) {
            return new McpResult(false, List.of("ok"));
        }
        @Override public boolean isHealthy() { return !closed; }
        @Override public void close() { closed = true; }
    }
}
