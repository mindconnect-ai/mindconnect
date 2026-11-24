package ai.mindconnect.llm.audio.port.in;

import ai.mindconnect.llm.audio.domain.SpeechRequest;
import ai.mindconnect.llm.audio.domain.SpeechResult;

/**
 * Turns text into speech — the opposite direction of
 * {@code LlmTranscription}. The caller names a config of type
 * {@code TEXT_TO_SPEECH}, or an alias pointing at one, so an operator decides
 * which model and voice answer a {@code text-to-speech} request.
 */
public interface LlmSpeech {

    /**
     * Speaks one piece of text with the named config.
     *
     * @throws ai.mindconnect.common.DomainException if no config has that name
     * @throws IllegalStateException if the config is not a text-to-speech
     *                               config, or the provider call fails
     */
    SpeechResult synthesize(String configName, SpeechRequest request);
}
