package ai.mindconnect.llm.domain;

/**
 * One recording to turn into text. The audio travels as the bytes of an
 * encoded file — what a browser's recorder or an upload hands over (WebM/Opus,
 * WAV, MP3, M4A, FLAC) — and the provider sniffs the container, so the
 * {@link #filename} matters: its extension is what most endpoints look at.
 *
 * @param audio       the encoded audio file's bytes; never empty
 * @param filename    file name including the extension, e.g. {@code speech.webm}
 * @param contentType the media type when known, e.g. {@code audio/webm};
 *                    {@code null} lets the adapter fall back to a generic one
 * @param language    ISO-639-1 code of the spoken language ({@code de}, {@code en}).
 *                    {@code null} leaves it to the model, which detects it.
 * @param prompt      optional context that steers spelling of names and terms
 */
public record TranscriptionRequest(
        byte[] audio,
        String filename,
        String contentType,
        String language,
        String prompt
) {
    public TranscriptionRequest {
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("audio must not be empty");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename must not be blank");
        }
    }

    /** A recording with no language hint and no prompt — the common case. */
    public static TranscriptionRequest of(byte[] audio, String filename, String contentType) {
        return new TranscriptionRequest(audio, filename, contentType, null, null);
    }

    /** The same recording, transcribed as the given language. */
    public TranscriptionRequest withLanguage(String language) {
        return new TranscriptionRequest(audio, filename, contentType, language, prompt);
    }

    /** The same recording, with context that steers spelling. */
    public TranscriptionRequest withPrompt(String prompt) {
        return new TranscriptionRequest(audio, filename, contentType, language, prompt);
    }
}
