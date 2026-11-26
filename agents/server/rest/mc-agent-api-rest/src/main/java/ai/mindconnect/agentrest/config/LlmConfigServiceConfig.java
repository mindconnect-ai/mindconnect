package ai.mindconnect.agentrest.config;

import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.management.api.port.out.LlmPriceRepository;
import ai.mindconnect.llm.service.LlmConfigService;
import ai.mindconnect.llm.service.LlmPriceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the REST API and the admin screens share for LLM configs, so that a
 * config saved or deleted through either leaves the same state behind.
 */
@Configuration
public class LlmConfigServiceConfig {

    /** A host without a price store keeps no prices; the configs are saved without them. */
    @Bean
    LlmConfigService llmConfigService(LlmConfigRepository configs, ObjectProvider<LlmPriceRepository> prices) {
        LlmPriceRepository priceRepository = prices.getIfAvailable();
        return new LlmConfigService(configs,
                priceRepository == null ? null : new LlmPriceService(priceRepository, configs));
    }
}
