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

class OverlayToolRegistryTest {

    private static final ToolCallScope SCOPE = ToolCallScope.detached(UserId.of("alice"));

    @Test
    void without_settings_everything_passes_through_untouched() {
        OverlayToolRegistry registry = overlay(new FakeRepository());

        assertThat(registry.knownToolNames()).containsExactly("glob", "web_search");
        Tool tool = registry.resolve(AgentTool.of("glob"), SCOPE).orElseThrow();
        assertThat(tool.description()).isEqualTo("description of glob");
    }

    @Test
    void a_disabled_tool_leaves_the_catalog_and_stops_resolving() {
        FakeRepository repository = new FakeRepository()
                .set("glob", new ToolSettings(false, null, Map.of()));
        OverlayToolRegistry registry = overlay(repository);

        assertThat(registry.knownToolNames()).containsExactly("web_search");
        assertThat(registry.toolNamesByGroup()).doesNotContainKey("files");
        assertThat(registry.resolve(AgentTool.of("glob"), SCOPE)).isEmpty();
    }

    @Test
    void an_agent_cannot_bring_a_disabled_tool_back() {
        // The operator's switch is policy, not a suggestion.
        FakeRepository repository = new FakeRepository()
                .set("glob", new ToolSettings(false, null, Map.of()));
        AgentTool bound = AgentTool.of("glob", "I really want this one");

        assertThat(overlay(repository).resolve(bound, SCOPE)).isEmpty();
    }

    @Test
    void an_operators_description_replaces_the_sources() {
        FakeRepository repository = new FakeRepository()
                .set("glob", new ToolSettings(null, "Finds files. Pass a project directory.", Map.of()));

        Tool tool = overlay(repository).resolve(AgentTool.of("glob"), SCOPE).orElseThrow();

        assertThat(tool.description()).isEqualTo("Finds files. Pass a project directory.");
        assertThat(tool.name()).isEqualTo("glob");
    }

    @Test
    void an_agents_own_description_wins_over_the_operators() {
        // Text inherits downwards: whoever builds the agent is more specific.
        FakeRepository repository = new FakeRepository()
                .set("glob", new ToolSettings(null, "operator text", Map.of()));

        Tool tool = overlay(repository).resolve(AgentTool.of("glob", "agent text"), SCOPE).orElseThrow();

        assertThat(tool.description()).isEqualTo("agent text");
    }

    @Test
    void the_operators_text_applies_where_the_agent_stays_silent() {
        FakeRepository repository = new FakeRepository()
                .set("glob", new ToolSettings(null, "operator text", Map.of()));

        Tool tool = overlay(repository).resolve(AgentTool.of("glob"), SCOPE).orElseThrow();

        assertThat(tool.description()).isEqualTo("operator text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parameter_descriptions_are_replaced_one_by_one() {
        FakeRepository repository = new FakeRepository().set("glob",
                new ToolSettings(null, null, Map.of("pattern", "Glob syntax: **/*.java")));

        Tool tool = overlay(repository).resolve(AgentTool.of("glob"), SCOPE).orElseThrow();

        Map<String, Object> properties = (Map<String, Object>) tool.parametersSchema().get("properties");
        assertThat((Map<String, Object>) properties.get("pattern"))
                .containsEntry("description", "Glob syntax: **/*.java")
                .containsEntry("type", "string");                 // structure untouched
        assertThat((Map<String, Object>) properties.get("path"))
                .containsEntry("description", "where to look");   // not mentioned, so unchanged
        assertThat(tool.parametersSchema()).containsEntry("required", List.of("path", "pattern"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_agents_description_does_not_take_the_parameter_texts_with_it() {
        // The agent's description wins over the operator's — but that settles
        // the tool's text, not what its `pattern` field says.
        FakeRepository repository = new FakeRepository().set("glob",
                new ToolSettings(null, "operator text", Map.of("pattern", "Glob syntax: **/*.java")));

        Tool tool = overlay(repository).resolve(AgentTool.of("glob", "agent text"), SCOPE).orElseThrow();

        assertThat(tool.description()).isEqualTo("agent text");
        Map<String, Object> properties = (Map<String, Object>) tool.parametersSchema().get("properties");
        assertThat((Map<String, Object>) properties.get("pattern"))
                .containsEntry("description", "Glob syntax: **/*.java");
    }

    @Test
    void the_source_is_reachable_beneath_the_decisions() {
        // What the admin UI compares an override against, and where the row
        // of a switched-off tool still comes from.
        FakeRepository repository = new FakeRepository()
                .set("glob", new ToolSettings(false, "operator text", Map.of()));
        OverlayToolRegistry registry = overlay(repository);

        ToolRegistry source = registry.source();

        assertThat(registry.knownToolNames()).doesNotContain("glob");
        assertThat(source.knownToolNames()).contains("glob");
        assertThat(source.resolve(AgentTool.of("glob"), SCOPE).orElseThrow().description())
                .isEqualTo("description of glob");
    }

    @Test
    void a_settings_change_is_noticed_without_a_restart() {
        FakeRepository repository = new FakeRepository();
        OverlayToolRegistry registry = overlay(repository);
        assertThat(registry.knownToolNames()).contains("glob");

        repository.set("glob", new ToolSettings(false, null, Map.of()));

        assertThat(registry.knownToolNames()).doesNotContain("glob");
    }

    @Test
    void an_unchanged_repository_is_read_once() {
        FakeRepository repository = new FakeRepository();
        OverlayToolRegistry registry = overlay(repository);
        registry.knownToolNames();
        int afterFirst = repository.reads;

        registry.knownToolNames();
        registry.knownToolNames();

        assertThat(repository.reads).isEqualTo(afterFirst);
    }

    private static OverlayToolRegistry overlay(ToolRepository repository) {
        return new OverlayToolRegistry(new FakeRegistry(), repository);
    }

    /** Two tools, one per group, with a small schema. */
    private static final class FakeRegistry implements ToolRegistry {

        @Override
        public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
            String name = AliasTool.registryName(agentTool);
            if (!name.equals("glob") && !name.equals("web_search")) {
                return Optional.empty();
            }
            Tool tool = new Tool() {
                @Override public String name() { return name; }
                @Override public String description() { return "description of " + name; }
                @Override public Map<String, Object> parametersSchema() {
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("path", new LinkedHashMap<>(Map.of(
                            "type", "string", "description", "where to look")));
                    properties.put("pattern", new LinkedHashMap<>(Map.of(
                            "type", "string", "description", "what to match")));
                    Map<String, Object> schema = new LinkedHashMap<>();
                    schema.put("type", "object");
                    schema.put("properties", properties);
                    schema.put("required", List.of("path", "pattern"));
                    return schema;
                }
                @Override public String execute(Map<String, Object> arguments) { return "ran " + name; }
            };
            // The same chain the real registry applies, so the agent-level
            // description is in play here exactly as it is in production.
            return Optional.of(SpiToolRegistry.decorate(agentTool, tool));
        }

        @Override public Set<String> knownToolNames() {
            return new LinkedHashSet<>(List.of("glob", "web_search"));
        }

        @Override public Map<String, Set<String>> toolNamesByGroup() {
            Map<String, Set<String>> byGroup = new LinkedHashMap<>();
            byGroup.put("files", Set.of("glob"));
            byGroup.put("web", Set.of("web_search"));
            return byGroup;
        }
    }

    /** Settings in memory, counting how often they were read. */
    private static final class FakeRepository implements ToolRepository {

        private final Map<String, ToolSettings> settings = new LinkedHashMap<>();
        private long version;
        int reads;

        FakeRepository set(String toolName, ToolSettings value) {
            settings.put(toolName, value);
            version++;
            return this;
        }

        @Override public ToolSettings settings(String toolName) {
            return settings.getOrDefault(toolName, ToolSettings.none());
        }

        @Override public Map<String, ToolSettings> all() {
            reads++;
            return Map.copyOf(settings);
        }

        @Override public void save(String toolName, ToolSettings value) {
            set(toolName, value);
        }

        @Override public void delete(String toolName) {
            settings.remove(toolName);
            version++;
        }

        @Override public long version() { return version; }
    }
}
