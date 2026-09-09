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

    /**
     * The same call, with the transcript handed over as it forms.
     * {@code onDelta} receives fragments in order; concatenated they are the
     * returned text, which is always the authority.
     *
     * <p>Whether anything actually arrives piecewise is the model's business.
     * A provider or model that answers in one piece calls the consumer once
     * at the end, so a caller's handling is the same either way — the default
     * implementation here does exactly that for a gateway that cannot stream
     * at all.
     */
    default TranscriptionResult transcribe(LlmConfig config, TranscriptionRequest request,
                                           java.util.function.Consumer<String> onDelta) {
        TranscriptionResult result = transcribe(config, request);
        if (onDelta != null && result.text() != null && !result.text().isEmpty()) {
            onDelta.accept(result.text());
        }
        return result;
    }
}
