package ai.mindconnect.llm.audio.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.llm.audio.domain.SpeechRequest;
import ai.mindconnect.llm.audio.domain.SpeechResult;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Routing by name: aliases followed, the wrong config type refused before the provider sees it. */
class RoutingLlmSpeechServiceTest {

    private final InMemoryConfigs configs = new InMemoryConfigs();
    private final List<LlmConfig> gatewayCalls = new ArrayList<>();
    private final RoutingLlmSpeechService service = new RoutingLlmSpeechService(configs, (config, request) -> {
        gatewayCalls.add(config);
        return new SpeechResult(request.text().getBytes(), "audio/mpeg");
    });

    @Test
    void routesToTheConfigBehindAnAlias() {
        configs.save(config("openai-tts", LlmConfigType.TEXT_TO_SPEECH));
        configs.save(LlmConfig.alias("text-to-speech", "openai-tts"));

        SpeechResult result = service.synthesize("text-to-speech", SpeechRequest.of("Hallo"));

        assertThat(result.contentType()).isEqualTo("audio/mpeg");
        assertThat(gatewayCalls).extracting(LlmConfig::name).containsExactly("openai-tts");
    }

    @Test
    void refusesAConfigOfAnotherType() {
        configs.save(config("gpt", LlmConfigType.CHAT));

        assertThatThrownBy(() -> service.synthesize("gpt", SpeechRequest.of("Hallo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a text-to-speech one");
        assertThat(gatewayCalls).isEmpty();
    }

    @Test
    void anUnknownNameIsNotFound() {
        assertThatThrownBy(() -> service.synthesize("nothing", SpeechRequest.of("Hallo")))
                .isInstanceOf(DomainException.class);
    }

    private static LlmConfig config(String name, LlmConfigType type) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.OPENAI, "tts-1", "http://localhost",
                "key", 0.0, 0, Map.of(), null, false, null, null, null, type, null);
    }

    private static final class InMemoryConfigs implements LlmConfigRepository {
        private final Map<String, LlmConfig> byName = new LinkedHashMap<>();

        @Override public void save(LlmConfig config) { byName.put(config.name(), config); }
        @Override public Optional<LlmConfig> findById(LlmConfigId id) {
            return byName.values().stream().filter(c -> c.id().equals(id)).findFirst();
        }
        @Override public Optional<LlmConfig> findByName(String name) { return Optional.ofNullable(byName.get(name)); }
        @Override public List<LlmConfig> findAll() { return List.copyOf(byName.values()); }
        @Override public void deleteById(LlmConfigId id) { byName.values().removeIf(c -> c.id().equals(id)); }
    }
}
