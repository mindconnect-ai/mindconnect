package ai.mindconnect.llm.gateway.service;

import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;

import ai.mindconnect.llm.management.api.domain.LlmConfig;

public interface LlmGatewayRegistry {

    /**
     * Returns the gateway capable of serving the given config.
     *
     * @throws IllegalArgumentException if no gateway is registered for the config's provider
     */
    LlmGateway gatewayFor(LlmConfig config);
}
