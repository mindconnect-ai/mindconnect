package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.registry.adapter.installer.AgentDefinitionInstaller;
import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionInstallerTest {

    private static final String JSON = """
            {
              "name": "web-researcher",
              "description": "Researches on the web",
              "group": "sub-agents",
              "icon": "telescope",
              "systemPrompt": "You research.",
              "llmConfigName": "default",
              "maxIterations": 20,
              "tools": []
            }
            """;

    private static final RegistryEntry ENTRY = new RegistryEntry("web-researcher",
            RegistryItemType.AGENT, "web-researcher", null, "1.0", "agents/web-researcher.json",
            List.of(), null, null, List.of());

    private FakeRepository repository;
    private AgentDefinitionInstaller installer;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        installer = new AgentDefinitionInstaller(repository);
    }

    @Test
    void installs_the_agent_with_its_prompt_and_an_active_status() throws Exception {
        ImportedItem item = installer.install(ENTRY, JSON, ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.IMPORTED);
        AgentDefinition saved = repository.findByName("web-researcher").orElseThrow();
        assertThat(saved.systemPrompt()).isEqualTo("You research.");
        assertThat(saved.group()).isEqualTo("sub-agents");
        assertThat(saved.maxIterations()).isEqualTo(20);
        assertThat(saved.status()).isEqualTo(AgentDefinitionStatus.ACTIVE);
        assertThat(saved.createdAt()).isNotNull();
    }

    @Test
    void an_overwrite_keeps_the_local_id_and_the_date_it_first_arrived() throws Exception {
        AgentDefinition existing = AgentDefinition.create("web-researcher", "old", "old prompt",
                null, "default");
        repository.save(existing);

        ImportedItem item = installer.install(ENTRY, JSON, ImportMode.OVERWRITE);

        assertThat(item.status()).isEqualTo(ImportStatus.UPDATED);
        AgentDefinition saved = repository.findByName("web-researcher").orElseThrow();
        assertThat(saved.id()).isEqualTo(existing.id());
        assertThat(saved.createdAt()).isEqualTo(existing.createdAt());
        assertThat(saved.systemPrompt()).isEqualTo("You research.");
    }

    @Test
    void keeps_what_is_here_under_the_default_mode() throws Exception {
        repository.save(AgentDefinition.create("web-researcher", "mine", "my prompt", null, "default"));

        ImportedItem item = installer.install(ENTRY, JSON, ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.SKIPPED);
        assertThat(repository.findByName("web-researcher").orElseThrow().systemPrompt())
                .isEqualTo("my prompt");
    }

    @Test
    void references_are_the_agents_running_on_a_config_or_calling_an_agent() {
        repository.save(AgentDefinition.create("web-researcher", "", "p", null, "default"));
        repository.save(AgentDefinition.create("lead", "", "p", null, "other")
                .withCallableAgents(List.of("web-researcher")));

        assertThat(installer.referencesTo(RegistryItemType.LLM_CONFIG, "default")).containsExactly("web-researcher");
        assertThat(installer.referencesTo(RegistryItemType.AGENT, "web-researcher")).containsExactly("lead");
        assertThat(installer.referencesTo(RegistryItemType.WORKFLOW, "web-researcher")).isEmpty();
    }

    @Test
    void remove_deletes_the_agent_of_the_entrys_name_and_skips_when_there_is_none() {
        repository.save(AgentDefinition.create("web-researcher", "mine", "my prompt", null, "default"));

        assertThat(installer.remove(ENTRY).status()).isEqualTo(ImportStatus.REMOVED);
        assertThat(repository.findByName("web-researcher")).isEmpty();
        assertThat(installer.remove(ENTRY).status()).isEqualTo(ImportStatus.SKIPPED);
    }

    private static final class FakeRepository implements AgentDefinitionRepository {
        private final List<AgentDefinition> agents = new ArrayList<>();

        @Override
        public AgentDefinition save(AgentDefinition definition) {
            agents.removeIf(a -> a.id().equals(definition.id()));
            agents.add(definition);
            return definition;
        }

        @Override
        public Optional<AgentDefinition> findById(AgentId id) {
            return agents.stream().filter(a -> a.id().equals(id)).findFirst();
        }

        @Override
        public List<AgentDefinition> findAll() {
            return List.copyOf(agents);
        }

        @Override
        public Optional<AgentDefinition> findByName(String name) {
            return agents.stream().filter(a -> name.equalsIgnoreCase(a.name())).findFirst();
        }

        @Override
        public void deleteById(AgentId id) {
            agents.removeIf(a -> a.id().equals(id));
        }
    }
}
