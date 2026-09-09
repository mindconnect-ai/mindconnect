package ai.mindconnect.llm.port.out;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;

/**
 * Adapter port to a provider's speech-to-text endpoint — what
 * {@link ai.mindconnect.llm.port.in.LlmTranscription} routes to once the
 * config behind a name is known. The config is concrete here: aliases are
 * already followed, the API key is still encrypted or an environment
 * placeholder, and resolving it is the adapter's job.
 */
public interface TranscriptionGateway {

    /**
     * Transcribes one recording.
     *
     * @throws IllegalStateException when the provider call fails or answers
     *                               with an error status
     */
    TranscriptionResult transcribe(LlmConfig config, TranscriptionRequest request);
}
