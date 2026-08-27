package ai.mindconnect.llm.domain;

/**
 * What a config's model does. One {@link LlmConfig} shape serves all types —
 * the type decides which settings apply (sampling knobs are chat-only) and
 * what a "test" means (chat turn vs text → vector).
 */
public enum LlmConfigType {
    /** Conversational / completion model — the default. */
    CHAT,
    /** Embedding model: turns text into vectors (vector stores, semantic search). */
    EMBEDDING
}
