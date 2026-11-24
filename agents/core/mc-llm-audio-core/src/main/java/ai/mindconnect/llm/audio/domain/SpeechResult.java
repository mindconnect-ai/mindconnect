package ai.mindconnect.llm.audio.domain;

/**
 * Spoken audio as an encoded file, ready to play or store.
 *
 * @param audio       the file's bytes
 * @param contentType its media type, e.g. {@code audio/mpeg}
 */
public record SpeechResult(byte[] audio, String contentType) {
}
