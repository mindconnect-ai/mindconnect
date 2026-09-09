package ai.mindconnect.llm.domain;

/**
 * What a config's model does. One {@link LlmConfig} shape serves all types —
 * the type decides which settings apply (sampling knobs are chat-only), which
 * port reaches the model, and what a "test" means (chat turn vs text → vector
 * vs audio → text).
 */
public enum LlmConfigType {
    /** Conversational / completion model — the default. */
    CHAT,
    /** Embedding model: turns text into vectors (vector stores, semantic search). */
    EMBEDDING,
    /**
     * Speech-to-text model: turns recorded audio into text (Whisper and the
     * OpenAI-compatible transcription endpoint). Reached through
     * {@link ai.mindconnect.llm.port.in.LlmTranscription}; sampling settings
     * and the context window do not apply.
     */
    SPEECH_TO_TEXT
}
