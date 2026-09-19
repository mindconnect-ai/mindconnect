package ai.mindconnect.adminui.setup;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.schema.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("What a picker offers by the set")
class ToolBundlesTest {

    /** Two Microsoft groups on one connection, an MCP group with two servers, one loose tool. */
    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public Optional<Tool> resolve(AgentTool tool, ToolCallScope scope) { return Optional.empty(); }
        @Override public Set<String> knownToolNames() {
            Set<String> all = new LinkedHashSet<>();
            toolNamesByGroup().values().forEach(all::addAll);
            return all;
        }
        @Override public Map<String, Set<String>> toolNamesByGroup() {
            Map<String, Set<String>> groups = new LinkedHashMap<>();
            groups.put("outlook", new LinkedHashSet<>(List.of("outlook_list", "outlook_read")));
            groups.put("calendar", new LinkedHashSet<>(List.of("calendar_list")));
            groups.put("mcp", new LinkedHashSet<>(List.of("gh_issues", "gh_prs", "jira_search")));
            groups.put("general", new LinkedHashSet<>(List.of("bash", "run_agent")));
            return groups;
        }
        @Override public String subgroupOf(String name) {
            return name.startsWith("gh_") ? "github" : name.startsWith("jira_") ? "jira" : null;
        }
        @Override public Optional<ConnectionSpec> connectionSpecOf(String name) {
            return name.startsWith("outlook_") || name.startsWith("calendar_")
                    ? Optional.of(ConnectionSpec.form("microsoft", "Microsoft account", Schema.object()))
                    : Optional.empty();
        }
    };

    private final ToolBundles bundles = ToolBundles.of(REGISTRY, name -> !name.equals("run_agent"));

    @Test
    @DisplayName("a group, its subgroups where it splits, and everything on one connection")
    void sets() {
        assertThat(bundles.all().stream().map(ToolBundles.Bundle::key))
                .containsSubsequence("group:calendar", "group:general", "group:mcp", "group:mcp/github",
                        "group:mcp/jira", "group:outlook", "provider:microsoft")
                .doesNotContain("group:outlook/", "provider:mcp");
    }

    @Test
    @DisplayName("then the single tools, minus what the caller keeps out")
    void singles() {
        List<String> singles = bundles.all().stream().filter(ToolBundles.Bundle::single)
                .map(ToolBundles.Bundle::key).toList();

        assertThat(singles).contains("tool:bash", "tool:gh_issues").doesNotContain("tool:run_agent");
    }

    @Test
    @DisplayName("labels say how many tools a set brings")
    void labels() {
        assertThat(bundles.all().stream().filter(b -> b.key().equals("provider:microsoft")).findFirst().get().label())
                .isEqualTo("Microsoft account — everything on it  (3 tools)");
        assertThat(bundles.all().stream().filter(b -> b.key().equals("group:calendar")).findFirst().get().label())
                .isEqualTo("Calendar  (1 tool)");
    }

    @Test
    @DisplayName("a pick expands to its tools, each once, sets in catalogue order")
    void expands() {
        // Groups come sorted, so the Microsoft set is calendar first, then outlook.
        assertThat(bundles.expand(List.of("provider:microsoft", "tool:outlook_list", "group:mcp/github", "tool:nope")))
                .containsExactly("calendar_list", "outlook_list", "outlook_read", "gh_issues", "gh_prs");
        assertThat(bundles.expand(null)).isEmpty();
    }
}
