package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
 * What a registry answers when asked what the installed tools need from a
 * user — the aggregation in {@link SpiToolRegistry} and what the two
 * decorators do with it.
 */
class DeclaredVariablesTest {

    @Test
    void a_factorys_declarations_are_stamped_with_the_tool_name() {
        SpiToolRegistry registry = registryOf(List.of(Factory.class), List.of());

        assertThat(registry.declaredVariables())
                .extracting(ToolVariable::name, ToolVariable::declaredBy)
                .containsExactly(tuple("FACTORY_KEY", "reads_things"));
    }

    @Test
    void a_providers_declarations_are_stamped_with_the_group() {
        // The group, not a tool name: the whole bundle shares one set of
        // credentials, and a user's profile should say "email", not "email_read_message".
        SpiToolRegistry registry = registryOf(List.of(), List.of(Provider.class));

        assertThat(registry.declaredVariables())
                .extracting(ToolVariable::name, ToolVariable::declaredBy)
                .containsExactly(tuple("MC_EMAIL_HOST", "email"), tuple("MC_EMAIL_PORT", "email"));
    }

    @Test
    void two_sources_asking_for_one_name_put_one_row_in_front_of_the_user() {
        SpiToolRegistry registry = registryOf(List.of(Factory.class), List.of(Colliding.class));

        assertThat(registry.declaredVariables()).extracting(ToolVariable::name)
                .containsExactly("FACTORY_KEY");
        assertThat(registry.declaredVariables().get(0).declaredBy()).isEqualTo("reads_things");
    }

    @Test
    void a_tool_this_installation_switched_off_takes_its_variables_with_it() {
        SpiToolRegistry source = registryOf(List.of(Factory.class), List.of(Provider.class));

        ToolRegistry offered = ConfiguredToolRegistry.of(source, "reads_things");

        assertThat(offered.declaredVariables()).extracting(ToolVariable::name)
                .containsExactly("MC_EMAIL_HOST", "MC_EMAIL_PORT");
    }

    @Test
    void switching_off_one_tool_of_a_bundle_leaves_the_bundles_credentials_alone() {
        // The rest of the bundle still needs them; the declaration is the group's.
        SpiToolRegistry source = registryOf(List.of(), List.of(Provider.class));

        ToolRegistry offered = ConfiguredToolRegistry.of(source, "email_read_message");

        assertThat(offered.declaredVariables()).extracting(ToolVariable::name)
                .containsExactly("MC_EMAIL_HOST", "MC_EMAIL_PORT");
    }

    @Test
    void an_operators_tool_settings_say_nothing_about_variables() {
        SpiToolRegistry source = registryOf(List.of(), List.of(Provider.class));
        ToolRepository settings = new ToolRepository() {
            @Override public ToolSettings settings(String toolName) {
                return new ToolSettings(false, null, Map.of());   // everything switched off
            }
            @Override public Map<String, ToolSettings> all() {
                return Map.of("email_read_message", new ToolSettings(false, null, Map.of()));
            }
            @Override public void save(String toolName, ToolSettings toolSettings) { }
            @Override public void delete(String toolName) { }
            @Override public long version() { return 1; }
        };

        assertThat(new OverlayToolRegistry(source, settings).declaredVariables())
                .extracting(ToolVariable::name)
                .containsExactly("MC_EMAIL_HOST", "MC_EMAIL_PORT");
    }

    @Test
    void a_registry_whose_sources_declare_nothing_answers_with_nothing() {
        assertThat(registryOf(List.of(), List.of()).declaredVariables()).isEmpty();
    }

    // ── the sources ─────────────────────────────────────────────────────────

    public static final class Factory implements ToolFactory {
        @Override public String name() { return "reads_things"; }
        @Override public String group() { return "things"; }
        @Override public List<ToolVariable> userVariables() {
            return List.of(ToolVariable.required("FACTORY_KEY", "A key", null));
        }
        @Override public Tool create(AgentTool agentTool, ToolCallScope scope) { return null; }
    }

    public static final class Provider implements MultiToolProvider {
        @Override public String group() { return "email"; }
        @Override public Set<String> toolNames() { return Set.of("email_read_message"); }
        @Override public List<ToolVariable> userVariables() {
            return List.of(ToolVariable.required("MC_EMAIL_HOST", "Host", null),
                    ToolVariable.withDefault("MC_EMAIL_PORT", "Port", null, "993"));
        }
        @Override public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }

    /** Wants the same name as {@link Factory}, worded differently. */
    public static final class Colliding implements MultiToolProvider {
        @Override public String group() { return "other"; }
        @Override public Set<String> toolNames() { return Set.of("other_tool"); }
        @Override public List<ToolVariable> userVariables() {
            return List.of(ToolVariable.optional("FACTORY_KEY", "The same key, differently worded", null));
        }
        @Override public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }

    // ── a class loader that serves exactly the services a test wants ────────

    private static SpiToolRegistry registryOf(List<Class<?>> factories, List<Class<?>> providers) {
        return new SpiToolRegistry(MapToolEnvironment.builder().build(),
                new ServicesClassLoader(Map.of(
                        "META-INF/services/" + ToolFactory.class.getName(), names(factories),
                        "META-INF/services/" + MultiToolProvider.class.getName(), names(providers))));
    }

    private static String names(List<Class<?>> classes) {
        return String.join("\n", classes.stream().map(Class::getName).toList());
    }

    /**
     * Serves one fixed set of service files and delegates everything else.
     * Registering the test's sources through {@code src/test/resources} would
     * put them into every other test in this module as well.
     */
    private static final class ServicesClassLoader extends ClassLoader {

        private final Map<String, String> services;

        ServicesClassLoader(Map<String, String> services) {
            super(DeclaredVariablesTest.class.getClassLoader());
            this.services = services;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            String content = services.get(name);
            if (content == null) {
                return super.getResources(name);
            }
            return Collections.enumeration(content.isBlank() ? List.of() : List.of(urlOf(content)));
        }

        private static URL urlOf(String content) throws IOException {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            return new URL("mem", null, 0, "/services", new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() { }
                        @Override public java.io.InputStream getInputStream() {
                            return new ByteArrayInputStream(bytes);
                        }
                    };
                }
            });
        }
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
