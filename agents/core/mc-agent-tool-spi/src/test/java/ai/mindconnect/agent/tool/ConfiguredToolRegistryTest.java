package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredToolRegistryTest {

    private static final ToolCallScope SCOPE = ToolCallScope.detached(UserId.of("alice"));

    @Test
    void nothing_disabled_is_the_registry_itself() {
        FakeRegistry registry = new FakeRegistry();

        assertThat(ConfiguredToolRegistry.of(registry, null)).isSameAs(registry);
        assertThat(ConfiguredToolRegistry.of(registry, "")).isSameAs(registry);
        assertThat(ConfiguredToolRegistry.of(registry, " , ")).isSameAs(registry);
    }

    @Test
    void the_list_is_read_forgivingly() {
        assertThat(ConfiguredToolRegistry.parse(" bash ,process_kill,, bash "))
                .containsExactly("bash", "process_kill");
    }

    @Test
    void a_disabled_tool_leaves_every_catalog() {
        ToolRegistry registry = ConfiguredToolRegistry.of(new FakeRegistry(), "bash,process_kill");

        assertThat(registry.knownToolNames()).containsExactly("glob", "web_search");
        assertThat(registry.toolNamesByGroup())
                .containsOnlyKeys("files", "web")
                .as("a group left without tools is no group")
                .doesNotContainKey("shell");
        assertThat(registry.overridesSchema("bash")).isEmpty();
        assertThat(registry.resolve(AgentTool.of("glob"), SCOPE)).isPresent();
    }

    @Test
    void an_agent_that_names_it_goes_without_under_any_name() {
        ToolRegistry registry = ConfiguredToolRegistry.of(new FakeRegistry(), "bash");

        assertThat(registry.resolve(AgentTool.of("bash", "run anything"), SCOPE)).isEmpty();
        assertThat(registry.resolve(AgentTool.of("shell", null, Map.of(AliasTool.OVERRIDE_KEY, "bash")), SCOPE))
                .as("an alias does not bring it back")
                .isEmpty();
    }

    @Test
    void tool_settings_cannot_switch_back_on_what_the_installation_does_not_offer() {
        FakeRepository repository = new FakeRepository()
                .set("bash", new ToolSettings(true, "enabled from the admin UI", Map.of()));
        OverlayToolRegistry registry = new OverlayToolRegistry(
                ConfiguredToolRegistry.of(new FakeRegistry(), "bash"), repository);

        assertThat(registry.knownToolNames()).doesNotContain("bash");
        assertThat(registry.resolve(AgentTool.of("bash"), SCOPE)).isEmpty();
    }

    @Test
    void the_source_beneath_still_knows_it() {
        // How the runtime reaches the SPI registry to warm it up.
        FakeRegistry spi = new FakeRegistry();
        ToolRegistry registry = new OverlayToolRegistry(
                ConfiguredToolRegistry.of(spi, "bash"), new FakeRepository());

        ToolRegistry inner = registry;
        while (inner.source() != inner) {
            inner = inner.source();
        }

        assertThat(inner).isSameAs(spi);
        assertThat(inner.knownToolNames()).contains("bash");
    }

    /** Three tools in three groups. */
    private static final class FakeRegistry implements ToolRegistry {

        private static final List<String> NAMES = List.of("glob", "web_search", "bash");

        @Override
        public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
            String name = AliasTool.registryName(agentTool);
            if (!NAMES.contains(name)) {
                return Optional.empty();
            }
            Tool tool = new Tool() {
                @Override public String name() { return name; }
                @Override public String description() { return "description of " + name; }
                @Override public Map<String, Object> parametersSchema() { return Map.of("type", "object"); }
                @Override public String execute(Map<String, Object> arguments) { return "ran " + name; }
            };
            return Optional.of(SpiToolRegistry.decorate(agentTool, tool));
        }

        @Override public Set<String> knownToolNames() {
            return new LinkedHashSet<>(NAMES);
        }

        @Override public Map<String, Set<String>> toolNamesByGroup() {
            Map<String, Set<String>> byGroup = new LinkedHashMap<>();
            byGroup.put("files", Set.of("glob"));
            byGroup.put("shell", Set.of("bash"));
            byGroup.put("web", Set.of("web_search"));
            return byGroup;
        }
    }

    /** Settings in memory. */
    private static final class FakeRepository implements ToolRepository {

        private final Map<String, ToolSettings> settings = new LinkedHashMap<>();
        private long version;

        FakeRepository set(String toolName, ToolSettings value) {
            settings.put(toolName, value);
            version++;
            return this;
        }

        @Override public ToolSettings settings(String toolName) {
            return settings.getOrDefault(toolName, ToolSettings.none());
        }

        @Override public Map<String, ToolSettings> all() { return Map.copyOf(settings); }

        @Override public void save(String toolName, ToolSettings value) { set(toolName, value); }

        @Override public void delete(String toolName) {
            settings.remove(toolName);
            version++;
        }

        @Override public long version() { return version; }
    }
}
