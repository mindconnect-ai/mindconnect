package ai.mindconnect.llm;

import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The capability set on {@link LlmConfig}: declared or not, stable in order,
 * carried by every copy, absent-tolerant in JSON. A config that declares
 * nothing reads as its provider's default; a declared set — empty included —
 * always wins.
 */
class LlmConfigCapabilitiesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static LlmConfig config(LlmProvider provider, Set<LlmCapability> capabilities) {
        return new LlmConfig(LlmConfigId.random(), "test", provider,
                "some-model", "https://example", "key", 0.7, 4096, Map.of(), null,
                false, null, null, null, null, capabilities);
    }

    private static LlmConfig config(Set<LlmCapability> capabilities) {
        return config(LlmProvider.OPENAI, capabilities);
    }

    @Test
    void notDeclaredReadsAsTheProvidersDefault() {
        var cfg = config(LlmProvider.ANTHROPIC, null);

        assertThat(cfg.declaresCapabilities()).isFalse();
        assertThat(cfg.capabilities()).isNull();
        assertThat(cfg.effectiveCapabilities())
                .containsExactly(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS);
        assertThat(cfg.supports(LlmCapability.VISION)).isTrue();
        assertThat(cfg.supports(LlmCapability.AUDIO_INPUT)).isFalse();
    }

    @Test
    void providersOfArbitraryModelsDefaultToToolCallingOnly() {
        for (LlmProvider p : Set.of(LlmProvider.LM_STUDIO, LlmProvider.OLLAMA, LlmProvider.OPENROUTER,
                LlmProvider.TOGETHER, LlmProvider.GROQ, LlmProvider.FIREWORKS)) {
            assertThat(config(p, null).supports(LlmCapability.VISION)).as(p.name()).isFalse();
            assertThat(config(p, null).supports(LlmCapability.TOOL_CALLING)).as(p.name()).isTrue();
        }
        assertThat(config(LlmProvider.GOOGLE_GEMINI, null).supports(LlmCapability.AUDIO_INPUT)).isTrue();
    }

    @Test
    void aDeclaredSetWinsOverTheDefault_theEmptySetIncluded() {
        var none = config(LlmProvider.ANTHROPIC, Set.of());
        assertThat(none.declaresCapabilities()).isTrue();
        assertThat(none.effectiveCapabilities()).isEmpty();
        assertThat(none.supports(LlmCapability.VISION)).isFalse();

        var vision = config(LlmProvider.LM_STUDIO, Set.of(LlmCapability.VISION));
        assertThat(vision.supports(LlmCapability.VISION)).isTrue();
        assertThat(vision.supports(LlmCapability.TOOL_CALLING)).isFalse();
    }

    @Test
    void factoriesAndAliasesDeclareNothing() {
        LlmConfig claude = LlmConfig.claude("c", "claude-sonnet-4-6", "k");
        assertThat(claude.declaresCapabilities()).isFalse();
        assertThat(claude.supports(LlmCapability.DOCUMENTS)).isTrue();

        LlmConfig alias = LlmConfig.alias("a", "c");
        assertThat(alias.declaresCapabilities()).isFalse();
        assertThat(alias.effectiveCapabilities()).as("no provider, nothing applies").isEmpty();
    }

    @Test
    void theSetIsUnmodifiableAndInDeclarationOrder() {
        var cfg = config(Set.of(LlmCapability.DOCUMENTS, LlmCapability.TOOL_CALLING, LlmCapability.VISION));
        assertThat(cfg.capabilities())
                .containsExactly(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS);
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
        assertThat(cfg.withCapabilities(null).declaresCapabilities()).isFalse();
    }

    @Test
    void jsonRoundTripKeepsTheSet_andKeepsNotDeclared() throws Exception {
        var cfg = config(Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION));
        String json = JSON.writeValueAsString(cfg);
        assertThat(json).contains("\"capabilities\":[\"TOOL_CALLING\",\"VISION\"]");
        assertThat(JSON.readValue(json, LlmConfig.class)).isEqualTo(cfg);

        var undeclared = config(null);
        LlmConfig read = JSON.readValue(JSON.writeValueAsString(undeclared), LlmConfig.class);
        assertThat(read.declaresCapabilities()).isFalse();
        assertThat(read).isEqualTo(undeclared);
    }

    @Test
    void jsonWithoutTheFieldLoadsAsNotDeclared() throws Exception {
        // A config persisted before capabilities existed: its provider decides.
        String legacy = """
                {"id":"00000001-0000-0000-0000-000000000002","name":"claude-default",
                 "provider":"ANTHROPIC","model":"claude-sonnet-4-6","apiKey":"k",
                 "defaultTemperature":0.7,"maxOutputTokens":8192}
                """;
        LlmConfig cfg = JSON.readerFor(LlmConfig.class)
                .readValue(legacy);
        assertThat(cfg.declaresCapabilities()).isFalse();
        assertThat(cfg.supports(LlmCapability.VISION)).isTrue();
        assertThat(cfg.name()).isEqualTo("claude-default");
        assertThat(cfg.id()).isEqualTo(LlmConfigId.of("00000001-0000-0000-0000-000000000002"));
    }
}
