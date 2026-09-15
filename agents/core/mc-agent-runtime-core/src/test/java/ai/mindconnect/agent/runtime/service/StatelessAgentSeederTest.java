package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A namespace that never ran a helper agent — the title generator, the
 * summarizers — gets it on first use, on an LLM config the namespace has.
 */
class StatelessAgentSeederTest {

    private final Map<AgentId, AgentDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, LlmConfig> configs = new LinkedHashMap<>();

    @Test
    void createsAKnownHelperOnTheConfiguredConfig() {
        StatelessAgentSeeder seeder = new StatelessAgentSeeder(defs(), llmConfigs(), "helpers-config");

        Optional<AgentDefinition> created = seeder.ensure(StatelessAgentSeeder.TITLE_GENERATOR);

        assertThat(created).isPresent();
        assertThat(created.get().llmConfigName()).isEqualTo("helpers-config");
        assertThat(defs().findByName(StatelessAgentSeeder.TITLE_GENERATOR)).contains(created.get());
    }

    @Test
    void withoutAConfiguredNameTheNamespacesAgentDefaultIsUsedElseItsFirstConfig() {
        StatelessAgentSeeder seeder = new StatelessAgentSeeder(defs(), llmConfigs(), null);
        config("openai-default");
        config("agent-default");

        assertThat(seeder.ensure(StatelessAgentSeeder.TITLE_GENERATOR)).map(AgentDefinition::llmConfigName)
                .contains("agent-default");

        configs.remove("agent-default");
        assertThat(seeder.ensure(StatelessAgentSeeder.CONVERSATION_SUMMARIZER)).map(AgentDefinition::llmConfigName)
                .contains("openai-default");
    }

    @Test
    void withoutAnyConfigNothingIsCreated() {
        StatelessAgentSeeder seeder = new StatelessAgentSeeder(defs(), llmConfigs(), null);

        assertThat(seeder.ensure(StatelessAgentSeeder.TITLE_GENERATOR)).isEmpty();
        assertThat(definitions).isEmpty();
    }

    @Test
    void anExistingHelperIsKeptAndAnUnknownNameIsNotOurs() {
        StatelessAgentSeeder seeder = new StatelessAgentSeeder(defs(), llmConfigs(), "helpers-config");
        AgentDefinition mine = AgentDefinition.create(StatelessAgentSeeder.TITLE_GENERATOR, "my own",
                "Name it my way.", null, "my-config");
        defs().save(mine);

        assertThat(seeder.ensure(StatelessAgentSeeder.TITLE_GENERATOR)).contains(mine);
        assertThat(seeder.ensure("web-researcher")).isEmpty();
        assertThat(StatelessAgentSeeder.knows("web-researcher")).isFalse();
    }

    private void config(String name) {
        configs.put(name, LlmConfig.lmStudio(name, "m", "http://localhost:1234"));
    }

    private AgentDefinitionRepository defs() {
        return new AgentDefinitionRepository() {
            @Override public AgentDefinition save(AgentDefinition d) { definitions.put(d.id(), d); return d; }
            @Override public Optional<AgentDefinition> findById(AgentId id) { return Optional.ofNullable(definitions.get(id)); }
            @Override public List<AgentDefinition> findAll() { return List.copyOf(definitions.values()); }
            @Override public Optional<AgentDefinition> findByName(String name) {
                return definitions.values().stream().filter(d -> d.name().equals(name)).findFirst();
            }
            @Override public void deleteById(AgentId id) { definitions.remove(id); }
        };
    }

    private LlmConfigRepository llmConfigs() {
        return new LlmConfigRepository() {
            @Override public void save(LlmConfig c) { configs.put(c.name(), c); }
            @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.empty(); }
            @Override public Optional<LlmConfig> findByName(String name) { return Optional.ofNullable(configs.get(name)); }
            @Override public List<LlmConfig> findAll() { return List.copyOf(configs.values()); }
            @Override public void deleteById(LlmConfigId id) { }
        };
    }
}
