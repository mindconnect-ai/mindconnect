package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelLabelTest {

    private final InMemoryLlmConfigRepository configs = new InMemoryLlmConfigRepository();

    @Test
    void a_config_shows_its_model() {
        configs.save(LlmConfig.claude("claude-default", "claude-sonnet-5", "key"));

        assertThat(ModelLabel.of(configs, "claude-default")).isEqualTo("claude-sonnet-5");
    }

    @Test
    void an_alias_shows_the_model_of_the_config_it_points_at() {
        configs.save(LlmConfig.claude("claude-default", "claude-sonnet-5", "key"));
        configs.save(LlmConfig.alias("agent-default", "claude-default"));

        assertThat(ModelLabel.of(configs, "agent-default")).isEqualTo("claude-sonnet-5");
    }

    @Test
    void without_anything_better_the_config_keeps_its_name() {
        configs.save(LlmConfig.alias("dangling", "nobody"));

        assertThat(ModelLabel.of(configs, "dangling")).isEqualTo("dangling");
        assertThat(ModelLabel.of(configs, "unknown")).isEqualTo("unknown");
        assertThat(ModelLabel.of(null, "agent-default")).isEqualTo("agent-default");
        assertThat(ModelLabel.of(configs, null)).isNull();
    }
}
