package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.domain.LlmConfigType;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Puts an OpenAI chat, embedding and transcription config into one namespace on
 * start, so a fresh database has something to call. A config already there by
 * name is left as it is: an edit made through the API survives a restart. The
 * key is the placeholder {@code ${OPENAI_API_KEY}}, resolved per call from the
 * environment, so no secret lands in the database.
 */
@Component
class OpenAiConfigSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenAiConfigSeed.class);
    private static final String KEY = "${OPENAI_API_KEY}";

    private final LlmConfigRepository configs;
    private final ThreadBoundScopeSupplier scope;
    private final String namespace;

    OpenAiConfigSeed(LlmConfigRepository configs, ThreadBoundScopeSupplier scope,
                     @Value("${mindconnect.llm.seed-namespace:default}") String namespace) {
        this.configs = configs;
        this.scope = scope;
        this.namespace = namespace;
    }

    @Override
    public void run(ApplicationArguments args) {
        scope.runIn(Scope.of(new Namespace(namespace)), () -> {
            for (LlmConfig config : seeds()) {
                if (configs.findByName(config.name()).isEmpty()) {
                    configs.save(config);
                    log.info("Seeded LLM config '{}' ({}) in namespace '{}'", config.name(), config.model(), namespace);
                }
            }
            return null;
        });
    }

    private static List<LlmConfig> seeds() {
        String baseUrl = LlmProvider.OPENAI.defaultBaseUrl();
        return List.of(
                LlmConfig.openAi("openai-chat", "gpt-5.4-mini", KEY),
                new LlmConfig(LlmConfigId.random(), "openai-embedding", LlmProvider.OPENAI,
                        "text-embedding-3-small", baseUrl, KEY, 0.0, 0, Map.of(), null, false, null,
                        null, null, LlmConfigType.EMBEDDING, null),
                LlmConfig.speechToText("openai-transcription", LlmProvider.OPENAI,
                        "gpt-4o-mini-transcribe", baseUrl, KEY));
    }
}
