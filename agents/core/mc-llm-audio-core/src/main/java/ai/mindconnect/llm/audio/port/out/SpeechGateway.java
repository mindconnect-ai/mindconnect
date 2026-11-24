package ai.mindconnect.llm.audio.port.out;

import ai.mindconnect.llm.audio.domain.SpeechRequest;
import ai.mindconnect.llm.audio.domain.SpeechResult;
import ai.mindconnect.llm.domain.LlmConfig;

/**
 * Adapter port to a provider's speech endpoint. The config is concrete here:
 * aliases are already followed, the API key is still encrypted or an
 * environment placeholder, and resolving it is the adapter's job.
 */
public interface SpeechGateway {

    /**
     * @throws IllegalStateException when the provider call fails or answers
     *                               with an error status
     */
    SpeechResult synthesize(LlmConfig config, SpeechRequest request);
}
