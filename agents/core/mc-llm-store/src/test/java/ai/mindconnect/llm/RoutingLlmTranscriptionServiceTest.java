package ai.mindconnect.llm;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import java.util.Map;
import ai.mindconnect.llm.adapter.env.LlmConfigResolver;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.llm.runtime.api.domain.UnknownLlmConfigException;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.runtime.api.domain.TranscriptionRequest;
import ai.mindconnect.llm.runtime.api.domain.TranscriptionResult;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.provider.spi.port.out.TranscriptionGateway;
import ai.mindconnect.llm.gateway.service.RoutingLlmTranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routing a transcription by config name: the alias a caller may name, the
 * config it lands on, and the two ways a name can be wrong.
 */
class RoutingLlmTranscriptionServiceTest {

    private static final TranscriptionRequest RECORDING = TranscriptionRequest.of(
            "audio".getBytes(StandardCharsets.UTF_8), "speech.webm", "audio/webm");

    private LlmConfigRepository repository;
    private RoutingLlmTranscriptionService service;
    private final AtomicReference<LlmConfig> used = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repository = new InMemoryLlmConfigRepository(ScopeSupplier.local());
        repository.save(LlmConfig.speechToText("whisper", LlmProvider.OPENAI, "whisper-1",
                "https://api.openai.com", "key"));

        TranscriptionGateway gateway = (config, request) -> {
            used.set(config);
            return TranscriptionResult.of("what the model heard");
        };
        service = new RoutingLlmTranscriptionService(repository, gateway, new LlmConfigResolver(EnvVarResolver.of(Map.of()), EncryptionHelper.noEncryption()));
    }

    @Test
    void theNamedConfigReachesTheGateway() {
        TranscriptionResult result = service.transcribe("whisper", RECORDING);

        assertThat(result.text()).isEqualTo("what the model heard");
        assertThat(used.get().model()).isEqualTo("whisper-1");
    }

    @Test
    void anAliasIsFollowedToTheConfigBehindIt() {
        repository.save(LlmConfig.alias("speech-to-text", "whisper"));

        service.transcribe("speech-to-text", RECORDING);

        // The alias carries no provider or model — what arrives at the
        // gateway has to be the config it points at.
        assertThat(used.get().name()).isEqualTo("whisper");
        assertThat(used.get().model()).isEqualTo("whisper-1");
    }

    @Test
    void anUnknownNameIsNotFound() {
        assertThatThrownBy(() -> service.transcribe("nobody", RECORDING))
                .isInstanceOf(UnknownLlmConfigException.class)
                .hasMessageContaining("nobody");
    }

    @Test
    void aChatConfigIsRefusedBeforeTheCall() {
        repository.save(LlmConfig.lmStudio("local", "gpt-oss", "http://localhost:1234"));

        assertThatThrownBy(() -> service.transcribe("local", RECORDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a speech-to-text one");
        assertThat(used.get()).as("nothing reached the gateway").isNull();
    }
}
