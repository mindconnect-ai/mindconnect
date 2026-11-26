package ai.mindconnect.llm.gateway.service;

import ai.mindconnect.llm.management.api.port.out.SecretResolver;
import ai.mindconnect.llm.runtime.api.domain.UnknownLlmConfigException;
import ai.mindconnect.llm.provider.spi.port.out.EmbeddingGateway;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.runtime.api.port.in.LlmEmbedding;

import java.util.List;

/** Embeds through the config behind a name, aliases followed, so that a caller never holds a config. */
public class RoutingLlmEmbeddingService implements LlmEmbedding {

    private final LlmConfigRepository configRepository;
    private final EmbeddingGateway gateway;
    private final SecretResolver secrets;

    public RoutingLlmEmbeddingService(LlmConfigRepository configRepository, EmbeddingGateway gateway,
                                      SecretResolver secrets) {
        this.configRepository = configRepository;
        this.gateway = gateway;
        this.secrets = secrets;
    }

    @Override
    public List<float[]> embed(String configName, List<String> texts) {
        return gateway.embed(secrets.resolve(configRepository.findResolvedByName(configName)
                .orElseThrow(() -> new UnknownLlmConfigException(configName))), texts);
    }
}
