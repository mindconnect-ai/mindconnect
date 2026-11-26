package ai.mindconnect.llm.runtime.api.port.in;

import java.util.List;

/**
 * Turns texts into embedding vectors through the config of the given name —
 * the ingestion side of vector search. Deliberately synchronous and batched:
 * callers (file ingestion) already run off the request thread and want one
 * round trip per chunk batch.
 */
public interface LlmEmbedding {

    /**
     * Embeds every text, returning vectors in input order. All vectors of one
     * call share the model's dimension; which model that is, the config says.
     */
    List<float[]> embed(String configName, List<String> texts);
}
