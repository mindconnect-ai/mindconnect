package ai.mindconnect.llm.service;

import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmGatewayRegistry;

import java.util.function.Consumer;

public class RoutingLlmChatService implements LlmChat {

    private final LlmConfigRepository configRepository;
    private final LlmGatewayRegistry gatewayRegistry;

    public RoutingLlmChatService(LlmConfigRepository configRepository, LlmGatewayRegistry gatewayRegistry) {
        this.configRepository = configRepository;
        this.gatewayRegistry = gatewayRegistry;
    }

    @Override
    public void chatStreaming(LlmRequest request,
                              Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        LlmConfig config = resolveConfig(request);
        gatewayRegistry.gatewayFor(config).chatStreaming(config, request, handler, cancellation, listener);
    }

    private LlmConfig resolveConfig(LlmRequest request) {
        LlmConfig config = configRepository.findByName(request.configName())
                .orElseThrow(() -> DomainException.notFound("LlmConfig", request.configName()));
        // Aliases point at another config by name — follow the chain to a concrete config.
        return config.resolveAlias(name -> configRepository.findByName(name).orElse(null));
    }
}
