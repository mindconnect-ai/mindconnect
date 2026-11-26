package ai.mindconnect.llm.provider.spi.port.out;

import ai.mindconnect.llm.management.api.domain.LlmConfig;

import java.util.List;

/**
 * One provider's embedding endpoint, called with the config itself: by the
 * gateway once it has resolved a name, and by whoever tests a config that is
 * not saved yet.
 */
public interface EmbeddingGateway {

    /**
     * Embeds every text, returning vectors in input order. All vectors of one
     * call share the model's dimension. The config's {@code model} names the
     * embedding model (e.g. {@code text-embedding-nomic-embed-text-v1.5} in
     * LM Studio).
     */
    List<float[]> embed(LlmConfig config, List<String> texts);
}
