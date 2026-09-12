package ai.mindconnect.agent.registry.adapter;

import ai.mindconnect.agent.registry.adapter.installer.LlmConfigInstaller;
import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigInstallerTest {

    private static final String JSON = """
            {
              "name": "default",
              "provider": "ANTHROPIC",
              "model": "claude-sonnet-5",
              "baseUrl": "https://api.anthropic.com",
              "apiKey": "%s",
              "maxOutputTokens": 8192
            }
            """;

    private static final RegistryEntry ENTRY = new RegistryEntry("default-llm",
            RegistryItemType.LLM_CONFIG, "default", null, "1.0", "llm-configs/default.json",
            List.of(), null, null, List.of());

    private FakeRepository repository;
    private LlmConfigInstaller installer;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        installer = new LlmConfigInstaller(repository);
    }

    @Test
    void installs_the_config_under_a_fresh_id() throws Exception {
        ImportedItem item = installer.install(ENTRY, JSON.formatted("${ANTHROPIC_API_KEY}"),
                ImportMode.SKIP_EXISTING);

        assertThat(item.status()).isEqualTo(ImportStatus.IMPORTED);
        LlmConfig saved = repository.findByName("default").orElseThrow();
        assertThat(saved.model()).isEqualTo("claude-sonnet-5");
        assertThat(saved.apiKey()).isEqualTo("${ANTHROPIC_API_KEY}");
        assertThat(saved.id()).isNotNull();
    }

    @Test
    void drops_an_api_key_that_came_with_the_file_and_says_so() throws Exception {
        ImportedItem item = installer.install(ENTRY, JSON.formatted("sk-ant-somebody-elses-key"),
                ImportMode.SKIP_EXISTING);

        assertThat(repository.findByName("default").orElseThrow().apiKey()).isNull();
        assertThat(item.detail()).contains("API key was dropped");
    }

    @Test
    void keeps_what_is_here_unless_the_mode_says_otherwise() throws Exception {
        repository.save(LlmConfig.claude("default", "claude-opus-5", "my-key"));

        ImportedItem skipped = installer.install(ENTRY, JSON.formatted("${KEY}"),
                ImportMode.SKIP_EXISTING);

        assertThat(skipped.status()).isEqualTo(ImportStatus.SKIPPED);
        assertThat(repository.findByName("default").orElseThrow().model()).isEqualTo("claude-opus-5");
    }

    @Test
    void an_overwrite_keeps_the_local_id_so_that_aliases_keep_pointing_at_it() throws Exception {
        LlmConfig existing = LlmConfig.claude("default", "claude-opus-5", "my-key");
        repository.save(existing);

        ImportedItem item = installer.install(ENTRY, JSON.formatted("${KEY}"), ImportMode.OVERWRITE);

        assertThat(item.status()).isEqualTo(ImportStatus.UPDATED);
        LlmConfig saved = repository.findByName("default").orElseThrow();
        assertThat(saved.id()).isEqualTo(existing.id());
        assertThat(saved.model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void exists_asks_the_store_by_name() {
        assertThat(installer.exists("default")).isFalse();
        repository.save(LlmConfig.claude("default", "claude-opus-5", "k"));
        assertThat(installer.exists("default")).isTrue();
    }

    @Test
    void references_are_the_aliases_delegating_to_a_config() throws Exception {
        repository.save(LlmConfig.claude("default", "claude-opus-5", "k"));
        installer.install(new RegistryEntry("alias", RegistryItemType.LLM_CONFIG, "agent-default", null, null,
                        "llm-configs/agent-default.json", List.of(), null, null, List.of()),
                "{\"name\":\"agent-default\",\"isAlias\":true,\"delegatesTo\":\"default\"}", ImportMode.OVERWRITE);

        assertThat(installer.referencesTo(RegistryItemType.LLM_CONFIG, "default")).containsExactly("agent-default");
        assertThat(installer.referencesTo(RegistryItemType.AGENT, "default")).isEmpty();
    }

    @Test
    void remove_deletes_the_config_of_the_entrys_name_and_skips_when_there_is_none() throws Exception {
        repository.save(LlmConfig.claude("default", "claude-opus-5", "k"));

        assertThat(installer.remove(ENTRY).status()).isEqualTo(ImportStatus.REMOVED);
        assertThat(repository.findByName("default")).isEmpty();
        assertThat(installer.remove(ENTRY).status()).isEqualTo(ImportStatus.SKIPPED);
    }

    /** A store that is a list — enough for what the installer does with one. */
    private static final class FakeRepository implements LlmConfigRepository {
        private final List<LlmConfig> configs = new ArrayList<>();

        @Override
        public void save(LlmConfig config) {
            configs.removeIf(c -> c.id().equals(config.id()));
            configs.add(config);
        }

        @Override
        public Optional<LlmConfig> findById(LlmConfigId id) {
            return configs.stream().filter(c -> c.id().equals(id)).findFirst();
        }

        @Override
        public Optional<LlmConfig> findByName(String name) {
            return configs.stream().filter(c -> name.equals(c.name())).findFirst();
        }

        @Override
        public List<LlmConfig> findAll() {
            return List.copyOf(configs);
        }

        @Override
        public void deleteById(LlmConfigId id) {
            configs.removeIf(c -> c.id().equals(id));
        }
    }
}
