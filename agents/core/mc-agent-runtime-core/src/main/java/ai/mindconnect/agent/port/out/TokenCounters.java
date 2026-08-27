package ai.mindconnect.agent.port.out;

/**
 * Resolves the {@link TokenCounter} to use for a model. The core budgets and
 * memory strategies only ever ask for a counter by model name — which
 * tokenizer actually answers (a real BPE encoder, a character-based estimate)
 * is an adapter concern, and keeping it behind this port is what lets the
 * core stay free of tokenizer libraries.
 */
public interface TokenCounters {

    /** The counter for {@code modelName}; never null — falls back when unknown. */
    TokenCounter forModel(String modelName);

    /** The counter used when no mapping matches. */
    TokenCounter fallback();

    /** Registers a counter for models matching {@code modelPattern}, taking precedence. */
    void register(String modelPattern, TokenCounter counter);
}
