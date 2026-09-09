package ai.mindconnect.llm.domain;

/**
 * What a speech-to-text model heard. Only {@link #text} is guaranteed —
 * language, duration and token counts are reported by some models and
 * endpoints and left empty by others.
 *
 * @param text            the transcript
 * @param language        the language the model detected, or {@code null}
 * @param durationSeconds length of the recording, or {@code null}
 * @param inputTokens     tokens billed for the audio, 0 when not reported
 * @param outputTokens    tokens billed for the transcript, 0 when not reported
 */
public record TranscriptionResult(
        String text,
        String language,
        Double durationSeconds,
        int inputTokens,
        int outputTokens
) {
    /** Just a transcript — what an endpoint returning plain text gives. */
    public static TranscriptionResult of(String text) {
        return new TranscriptionResult(text, null, null, 0, 0);
    }
}
