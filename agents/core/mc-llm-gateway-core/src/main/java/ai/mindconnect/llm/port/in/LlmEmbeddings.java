package ai.mindconnect.llm.port.in;

import ai.mindconnect.llm.domain.LlmConfig;

import java.util.List;

/**
 * Turns texts into embedding vectors using an {@link LlmConfig}'s provider —
 * the ingestion side of vector search. Deliberately synchronous and batched:
 * callers (file ingestion) already run off the request thread and want one
 * round trip per chunk batch.
 */
public interface LlmEmbeddings {

    /**
     * Embeds every text, returning vectors in input order. All vectors of one
     * call share the model's dimension. The config's {@code model} names the
     * embedding model (e.g. {@code text-embedding-nomic-embed-text-v1.5} in
     * LM Studio).
     */
    List<float[]> embed(LlmConfig config, List<String> texts);
}
