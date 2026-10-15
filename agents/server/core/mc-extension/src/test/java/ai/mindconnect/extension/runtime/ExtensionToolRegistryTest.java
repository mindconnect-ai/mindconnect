package ai.mindconnect.extension.runtime;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.extension.adapter.memory.InMemoryExtensionActivationRepository;
import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.service.ExtensionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionToolRegistryTest {

    private static final ExtensionId ACME = ExtensionId.of("acme-crm");

    /** A tool that is only there to be resolved. */
    private record Stub(String name) implements Tool {
        @Override public String description() { return name; }
        @Override public Map<String, Object> parametersSchema() { return Map.of(); }
        @Override public String execute(Map<String, Object> arguments) { return ""; }
    }

    /** A registry that knows two tools of Acme and one of the host. */
    private static final class Delegate implements ToolRegistry {
        @Override public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
            return Optional.of(new Stub(agentTool.name()));
        }
        @Override public Set<String> knownToolNames() { return Set.of("acme_contacts", "acme_leads", "bash"); }
        @Override public Map<String, Set<String>> toolNamesByGroup() {
            return Map.of("crm", Set.of("acme_contacts", "acme_leads"), "system", Set.of("bash"));
        }
    }

    private static final InMemoryExtensionActivationRepository DECISIONS = new InMemoryExtensionActivationRepository();

    private final ExtensionService extensions = new ExtensionService(new ExtensionRegistry(List.of(
            new Extension(new ExtensionManifest(ACME, null, null, null, null, null, null, null, null,
                    new ExtensionManifest.Contributes(new ExtensionManifest.Tools(null, List.of("acme_*")),
                            null, null, null, null, null, null, null)), "acme.jar"))),
            new InMemoryExtensionActivationRepository());

    private final ExtensionToolRegistry registry = new ExtensionToolRegistry(new Delegate(), extensions);

    @Test
    void while_the_extension_is_on_nothing_changes() {
        assertThat(registry.knownToolNames()).containsExactly("acme_contacts", "acme_leads", "bash");
        assertThat(registry.resolve(AgentTool.of("acme_contacts"), null)).isPresent();
        assertThat(registry.toolNamesByGroup()).containsOnlyKeys("crm", "system");
    }

    @Test
    void a_switched_off_extension_s_tools_are_gone() {
        extensions.disable(ACME, null);

        assertThat(registry.knownToolNames()).containsExactly("bash");
        assertThat(registry.toolNamesByGroup()).containsOnlyKeys("system");
        assertThat(registry.resolve(AgentTool.of("acme_contacts"), null)).isEmpty();
        assertThat(registry.resolve(AgentTool.of("bash"), null)).isPresent();
    }

    @Test
    void without_a_scope_nothing_is_hidden() {
        ExtensionService unbound = new ExtensionService(ExtensionRegistry.empty(), DECISIONS) {
            @Override public boolean hidesTool(String toolName) {
                throw new IllegalStateException("No scope is bound to thread");
            }
        };

        assertThat(new ExtensionToolRegistry(new Delegate(), unbound).knownToolNames()).hasSize(3);
        assertThat(new ExtensionToolRegistry(new Delegate(), unbound).resolve(AgentTool.of("acme_leads"), null))
                .isPresent();
    }
}
