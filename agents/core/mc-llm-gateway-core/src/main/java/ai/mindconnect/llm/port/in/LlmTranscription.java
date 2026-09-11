package ai.mindconnect.llm.port.in;


import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;

/**
 * Turns recorded speech into text. The counterpart of {@link LlmChat} for
 * audio: the caller names a config of type
 * {@link ai.mindconnect.llm.domain.LlmConfigType#SPEECH_TO_TEXT} and gets one
 * transcript back — no streaming, no conversation. Routing by name means an
 * alias works here as it does for chat models: point {@code speech-to-text}
 * at whichever config should serve it and callers stay unchanged.
 *
 * <p>An application provides an implementation as a bean; hosts that offer no
 * speech input simply have none, and callers check for its absence.
 */
public interface LlmTranscription {

    /**
     * Transcribes one recording with the named config.
     *
     * @param configName the config's name, or the name of an alias pointing at it
     * @throws ai.mindconnect.common.DomainException if no config has that name
     * @throws IllegalStateException                           if the config is not a
     *                                                         speech-to-text config, or
     *                                                         the provider call fails
     */
    TranscriptionResult transcribe(String configName, TranscriptionRequest request);

    /**
     * The same call, with the transcript handed over as it forms — for a
     * caller that shows it while it arrives. {@code onDelta} receives
     * fragments in order; their concatenation is the returned text, which
     * stays the authority.
     *
     * <p>A model that answers in one piece calls the consumer once at the
     * end. Nothing about the caller's code changes with the model.
     */
    default TranscriptionResult transcribe(String configName, TranscriptionRequest request,
                                           java.util.function.Consumer<String> onDelta) {
        TranscriptionResult result = transcribe(configName, request);
        if (onDelta != null && result.text() != null && !result.text().isEmpty()) {
            onDelta.accept(result.text());
        }
        return result;
    }
}
