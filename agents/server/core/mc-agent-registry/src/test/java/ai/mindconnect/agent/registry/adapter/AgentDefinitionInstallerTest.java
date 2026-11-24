package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.registry.adapter.installer.AgentDefinitionInstaller;
import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.management.RepositoryAgentManagement;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import java.util.Map;
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

    private AgentManagement repository;
    private AgentDefinitionInstaller installer;

    @BeforeEach
    void setUp() {
        repository = new RepositoryAgentManagement(new InMemoryAgentRepository(NamespaceProvider.fixed("test")));
        installer = new AgentDefinitionInstaller(repository);
    }

    @Test
    void installs_the_agent_with_its_prompt_and_an_active_status() throws Exception {
        ImportedItem item = installer.install(ENTRY, JSON, ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.IMPORTED);
        ManagedAgent saved = repository.findByName("web-researcher").orElseThrow();
        assertThat(saved.definition().instructions()).isEqualTo("You research.");
        assertThat(saved.group()).isEqualTo("sub-agents");
        assertThat(saved.definition().maxRounds()).isEqualTo(20);
        assertThat(saved.status()).isEqualTo(ManagedAgent.Status.ACTIVE);
        assertThat(saved.createdAt()).isNotNull();
    }

    @Test
    void carries_the_skills_setting_of_the_registry_file() throws Exception {
        String json = JSON.replace("\"tools\": []",
                "\"tools\": [], \"skills\": {\"enabled\": true, \"names\": [\"weekly-report\"]}");

        installer.install(ENTRY, json, ImportMode.SKIP_EXISTING);

        assertThat(repository.findByName("web-researcher").orElseThrow().definition().metadata())
                .containsEntry(SkillPlugin.SKILLS, List.of("weekly-report"));
    }

    @Test
    void an_overwrite_keeps_the_local_id_and_the_date_it_first_arrived() throws Exception {
        ManagedAgent existing = agent("web-researcher", "old", "old prompt", "default", Map.of());

        ImportedItem item = installer.install(ENTRY, JSON, ImportMode.OVERWRITE);

        assertThat(item.status()).isEqualTo(ImportStatus.UPDATED);
        ManagedAgent saved = repository.findByName("web-researcher").orElseThrow();
        assertThat(saved.id()).isEqualTo(existing.id());
        assertThat(saved.createdAt()).isEqualTo(existing.createdAt());
        assertThat(saved.definition().instructions()).isEqualTo("You research.");
    }

    @Test
    void keeps_what_is_here_under_the_default_mode() throws Exception {
        agent("web-researcher", "mine", "my prompt", "default", Map.of());

        ImportedItem item = installer.install(ENTRY, JSON, ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.SKIPPED);
        assertThat(repository.findByName("web-researcher").orElseThrow().definition().instructions())
                .isEqualTo("my prompt");
    }

    @Test
    void references_are_the_agents_running_on_a_config_or_calling_an_agent() {
        agent("web-researcher", "", "p", "default", Map.of());
        agent("lead", "", "p", "other", Map.of(SubAgentPlugin.SUB_AGENTS, List.of("web-researcher")));

        assertThat(installer.referencesTo(RegistryItemType.LLM_CONFIG, "default")).containsExactly("web-researcher");
        assertThat(installer.referencesTo(RegistryItemType.AGENT, "web-researcher")).containsExactly("lead");
        assertThat(installer.referencesTo(RegistryItemType.WORKFLOW, "web-researcher")).isEmpty();
    }

    @Test
    void references_to_a_skill_are_the_agents_naming_it_not_the_ones_loading_all() {
        agent("writer", "", "p", "default", Map.of(SkillPlugin.SKILLS, List.of("weekly-report")));
        agent("everything", "", "p", "default", Map.of());

        assertThat(installer.referencesTo(RegistryItemType.SKILL, "weekly-report")).containsExactly("writer");
        assertThat(installer.referencesTo(RegistryItemType.SKILL, "other")).isEmpty();
    }

    @Test
    void remove_deletes_the_agent_of_the_entrys_name_and_skips_when_there_is_none() {
        agent("web-researcher", "mine", "my prompt", "default", Map.of());

        assertThat(installer.remove(ENTRY).status()).isEqualTo(ImportStatus.REMOVED);
        assertThat(repository.findByName("web-researcher")).isEmpty();
        assertThat(installer.remove(ENTRY).status()).isEqualTo(ImportStatus.SKIPPED);
    }

    private ManagedAgent agent(String name, String description, String prompt, String model,
                               Map<String, Object> metadata) {
        return repository.create(ManagedAgent.of(new AgentDefinition(name, description, prompt,
                ModelSettings.ofModel(model), List.of(), 10, metadata)));
    }
}
