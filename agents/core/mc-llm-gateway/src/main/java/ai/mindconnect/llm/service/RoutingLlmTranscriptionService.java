package ai.mindconnect.llm.service;


import ai.mindconnect.common.DomainException;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.TranscriptionGateway;

/**
 * Routes a transcription by config name, the way
 * {@link RoutingLlmChatService} routes a chat: look the name up, follow an
 * alias to the config behind it, hand the call to the gateway. Callers name
 * {@code speech-to-text} and an operator decides what that is.
 */
public class RoutingLlmTranscriptionService implements LlmTranscription {

    private final LlmConfigRepository configRepository;
    private final TranscriptionGateway gateway;

    public RoutingLlmTranscriptionService(LlmConfigRepository configRepository,
                                          TranscriptionGateway gateway) {
        this.configRepository = configRepository;
        this.gateway = gateway;
    }

    @Override
    public TranscriptionResult transcribe(String configName, TranscriptionRequest request) {
        return gateway.transcribe(resolveConfig(configName), request);
    }

    @Override
    public TranscriptionResult transcribe(String configName, TranscriptionRequest request,
                                          java.util.function.Consumer<String> onDelta) {
        return gateway.transcribe(resolveConfig(configName), request, onDelta);
    }

    /**
     * The config behind the name, aliases followed. A config of the wrong type
     * is refused here rather than at the provider: sending a chat model to the
     * transcription endpoint fails with a message about the endpoint, which
     * says nothing about the actual mistake.
     */
    private LlmConfig resolveConfig(String configName) {
        LlmConfig config = configRepository.findResolvedByName(configName)
                .orElseThrow(() -> DomainException.notFound("LlmConfig", configName));
        if (config.type() != LlmConfigType.SPEECH_TO_TEXT) {
            throw new IllegalStateException("LLM config '" + configName + "' is a "
                    + config.type() + " config, not a speech-to-text one");
        }
        return config;
    }
}
