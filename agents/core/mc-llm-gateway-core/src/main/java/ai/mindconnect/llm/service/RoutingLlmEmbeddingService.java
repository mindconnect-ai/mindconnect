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

    public RoutingLlmEmbeddingService(LlmConfigRepository configRepository, EmbeddingGateway gateway) {
        this.configRepository = configRepository;
        this.gateway = gateway;
    }

    @Override
    public List<float[]> embed(String configName, List<String> texts) {
        return gateway.embed(configRepository.findResolvedByName(configName)
                .orElseThrow(() -> DomainException.notFound("LlmConfig", configName)), texts);
    }
}
