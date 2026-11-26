package ai.mindconnect.llm.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.llm.provider.spi.port.out.EmbeddingGateway;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.runtime.api.port.in.LlmEmbedding;

import java.util.List;

/** Embeds through the config behind a name, aliases followed, so that a caller never holds a config. */
public class RoutingLlmEmbeddingService implements LlmEmbedding {

    private final LlmConfigRepository configRepository;
    private final EmbeddingGateway gateway;
    private final LlmConfigResolver resolver;

    public RoutingLlmEmbeddingService(LlmConfigRepository configRepository, EmbeddingGateway gateway,
                                      LlmConfigResolver resolver) {
        this.configRepository = configRepository;
        this.gateway = gateway;
        this.resolver = resolver;
    }

    @Override
    public List<float[]> embed(String configName, List<String> texts) {
        return gateway.embed(resolver.resolve(configRepository.findResolvedByName(configName)
                .orElseThrow(() -> DomainException.notFound("LlmConfig", configName))), texts);
    }
}
