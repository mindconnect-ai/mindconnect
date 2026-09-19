package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tester is found by the provider it declares, through the same sources
 * that declare the spec — and a source that declares the spec without a
 * tester leaves the page without a Test button rather than with a broken one.
 */
@DisplayName("The registry finds a connection tester")
class ConnectionTesterTest {

    @Test
    @DisplayName("by the provider the source declares")
    void byProvider() {
        SpiToolRegistry registry = registryOf(List.of(Tested.class));

        Optional<ConnectionTester> tester = registry.connectionTesterOf("mailbox");

        assertThat(tester).isPresent();
        assertThat(tester.get().test(null).message()).isEqualTo("signed in");
    }

    @Test
    @DisplayName("and answers empty for a provider whose source offers none")
    void noneWhenNotOffered() {
        SpiToolRegistry registry = registryOf(List.of(Untested.class));

        assertThat(registry.connectionTesterOf("files")).isEmpty();
        assertThat(registry.connectionTesterOf("nobody")).isEmpty();
        assertThat(registry.connectionTesterOf(null)).isEmpty();
    }

    // ── sources ─────────────────────────────────────────────────────────────

    public static final class Tested implements MultiToolProvider {
        @Override public Set<String> toolNames() { return Set.of("mail_read"); }
        @Override public ConnectionSpec connectionSpec() {
            return ConnectionSpec.form("mailbox", "Mailbox", ai.mindconnect.schema.Schema.object());
        }
        @Override public Optional<ConnectionTester> connectionTester() {
            return Optional.of(connection -> ConnectionTest.ok("signed in"));
        }
        @Override public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }

    public static final class Untested implements MultiToolProvider {
        @Override public Set<String> toolNames() { return Set.of("file_list"); }
        @Override public ConnectionSpec connectionSpec() {
            return ConnectionSpec.form("files", "Files", ai.mindconnect.schema.Schema.object());
        }
        @Override public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }

    // ── a class loader that serves exactly these providers ──────────────────

    private static SpiToolRegistry registryOf(List<Class<?>> providers) {
        String names = String.join("\n", providers.stream().map(Class::getName).toList());
        return new SpiToolRegistry(MapToolEnvironment.builder().build(), new ServicesClassLoader(
                Map.of("META-INF/services/" + MultiToolProvider.class.getName(), names,
                       "META-INF/services/" + ToolFactory.class.getName(), "")));
    }

    private static final class ServicesClassLoader extends ClassLoader {

        private final Map<String, String> services;

        ServicesClassLoader(Map<String, String> services) {
            super(ConnectionTesterTest.class.getClassLoader());
            this.services = services;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            String content = services.get(name);
            if (content == null) return super.getResources(name);
            URL url = new URL("memory", null, 0, name, new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL u) {
                    return new URLConnection(u) {
                        @Override public void connect() { }
                        @Override public InputStream getInputStream() {
                            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
                        }
                    };
                }
            });
            return Collections.enumeration(List.of(url));
        }
    }
}
