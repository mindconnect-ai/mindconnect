package ai.mindconnect.llm.service;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmGateway;
import ai.mindconnect.llm.port.out.LlmGatewayRegistry;

import java.util.HashMap;
import java.util.Map;

public class DefaultLlmGatewayRegistry implements LlmGatewayRegistry {

    private final Map<LlmProvider, LlmGateway> gateways;

    public DefaultLlmGatewayRegistry(Map<LlmProvider, LlmGateway> gateways) {
        // Throttling outside Retrying, so one rate-limit permit covers a whole
        // call including its 429/529 retries.
        Map<LlmProvider, LlmGateway> wrapped = new HashMap<>();
        gateways.forEach((provider, gateway) ->
                wrapped.put(provider, new ThrottlingLlmGateway(new RetryingLlmGateway(gateway))));
        this.gateways = Map.copyOf(wrapped);
    }

    @Override
    public LlmGateway gatewayFor(LlmConfig config) {
        LlmGateway gateway = gateways.get(config.provider());
        if (gateway == null) {
            throw new IllegalArgumentException(
                    "No LlmGateway registered for provider: " + config.provider()
                    + " (config: " + config.name() + ")");
        }
        return gateway;
    }
}
