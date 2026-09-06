package ai.mindconnect.llm;

import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The capability set on {@link LlmConfig}: always present, stable in order,
 * carried by every copy, and absent-tolerant in JSON so configs written before
 * the field existed still load.
 */
class LlmConfigCapabilitiesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static LlmConfig config(Set<LlmCapability> capabilities) {
        return new LlmConfig(UUID.randomUUID(), "test", LlmProvider.OPENAI,
                "gpt-5", "https://api.openai.com", "key", 0.7, 4096, Map.of(), null,
                false, null, null, null, null, capabilities);
    }

    @Test
    void nullReadsAsTheEmptySet() {
        var cfg = config(null);
        assertThat(cfg.capabilities()).isEmpty();
        assertThat(cfg.supports(LlmCapability.VISION)).isFalse();
    }

    @Test
    void factoriesDeclareNothing() {
        assertThat(LlmConfig.claude("c", "claude-sonnet-4-6", "k").capabilities()).isEmpty();
        assertThat(LlmConfig.alias("a", "c").capabilities()).isEmpty();
    }

    @Test
    void theSetIsUnmodifiableAndInDeclarationOrder() {
        var cfg = config(Set.of(LlmCapability.DOCUMENTS, LlmCapability.TOOL_CALLING, LlmCapability.VISION));
        assertThat(cfg.capabilities())
                .containsExactly(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS);
        assertThat(cfg.supports(LlmCapability.VISION)).isTrue();
        assertThat(cfg.supports(LlmCapability.AUDIO_INPUT)).isFalse();
        assertThatThrownBy(() -> cfg.capabilities().add(LlmCapability.AUDIO_INPUT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesCarryTheSet() {
        var cfg = config(Set.of(LlmCapability.VISION));
        assertThat(cfg.withApiKey("other").capabilities()).containsExactly(LlmCapability.VISION);
        assertThat(cfg.withContextWindowTokens(1000).capabilities()).containsExactly(LlmCapability.VISION);
        assertThat(cfg.resolved().capabilities()).containsExactly(LlmCapability.VISION);
        assertThat(cfg.withCapabilities(Set.of(LlmCapability.AUDIO_INPUT)).capabilities())
                .containsExactly(LlmCapability.AUDIO_INPUT);
        assertThat(cfg.withCapabilities(null).capabilities()).isEmpty();
    }

    @Test
    void jsonRoundTripKeepsTheSet() throws Exception {
        var cfg = config(Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION));
        String json = JSON.writeValueAsString(cfg);
        assertThat(json).contains("\"capabilities\":[\"TOOL_CALLING\",\"VISION\"]");
        assertThat(JSON.readValue(json, LlmConfig.class)).isEqualTo(cfg);
    }

    @Test
    void jsonWithoutTheFieldLoadsAsTheEmptySet() throws Exception {
        // A config persisted before capabilities existed.
        String legacy = """
                {"id":"00000001-0000-0000-0000-000000000002","name":"claude-default",
                 "provider":"ANTHROPIC","model":"claude-sonnet-4-6","apiKey":"k",
                 "defaultTemperature":0.7,"maxOutputTokens":8192}
                """;
        LlmConfig cfg = JSON.readValue(legacy, LlmConfig.class);
        assertThat(cfg.capabilities()).isEmpty();
        assertThat(cfg.name()).isEqualTo("claude-default");
    }
}
