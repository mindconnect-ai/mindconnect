package ai.mindconnect.llm.port.out;

import ai.mindconnect.llm.domain.LlmConfig;

public interface LlmGatewayRegistry {

    /**
     * Returns the gateway capable of serving the given config.
     *
     * @throws IllegalArgumentException if no gateway is registered for the config's provider
     */
    LlmGateway gatewayFor(LlmConfig config);
}
