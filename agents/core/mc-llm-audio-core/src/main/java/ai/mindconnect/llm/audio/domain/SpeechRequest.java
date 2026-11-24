package ai.mindconnect.llm.audio.domain;

/**
 * One piece of text to speak. Voice and format are optional: what the request
 * leaves open, the config's additional parameters decide, and after them the
 * adapter's defaults.
 *
 * @param text   what to say; never blank
 * @param voice  the provider's voice name, e.g. {@code nova}; {@code null} for the config's
 * @param format the audio format, e.g. {@code mp3}, {@code opus}, {@code wav};
 *               {@code null} for the config's
 */
public record SpeechRequest(String text, String voice, String format) {

    public SpeechRequest {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }

    /** Text in the config's voice and format — the common case. */
    public static SpeechRequest of(String text) {
        return new SpeechRequest(text, null, null);
    }
}
