package ai.mindconnect.llm.audio.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.llm.audio.domain.SpeechRequest;
import ai.mindconnect.llm.audio.domain.SpeechResult;
import ai.mindconnect.llm.audio.port.in.LlmSpeech;
import ai.mindconnect.llm.audio.port.out.SpeechGateway;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

/**
 * Routes speech by config name the way transcription and chat are routed:
 * look the name up, follow an alias, hand the call to the gateway. A config
 * of the wrong type is refused here, because the provider's answer to a chat
 * model on the speech endpoint says nothing about the actual mistake.
 */
public class RoutingLlmSpeechService implements LlmSpeech {

    private final LlmConfigRepository configRepository;
    private final SpeechGateway gateway;

    public RoutingLlmSpeechService(LlmConfigRepository configRepository, SpeechGateway gateway) {
        this.configRepository = configRepository;
        this.gateway = gateway;
    }

    @Override
    public SpeechResult synthesize(String configName, SpeechRequest request) {
        LlmConfig config = configRepository.findResolvedByName(configName)
                .orElseThrow(() -> DomainException.notFound("LlmConfig", configName));
        if (config.type() != LlmConfigType.TEXT_TO_SPEECH) {
            throw new IllegalStateException("LLM config '" + configName + "' is a "
                    + config.type() + " config, not a text-to-speech one");
        }
        return gateway.synthesize(config, request);
    }
}
