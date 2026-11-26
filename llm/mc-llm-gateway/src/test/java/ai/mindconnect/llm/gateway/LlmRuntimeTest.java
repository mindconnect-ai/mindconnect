package ai.mindconnect.llm.gateway;

import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.runtime.api.domain.FinishReason;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRuntimeTest {

    private final AtomicReference<LlmConfig> seen = new AtomicReference<>();

    /** Answers with the model it was called with, and remembers the config it got. */
    private final LlmGateway echo = (config, request, handler, cancellation, listener) -> {
        seen.set(config);
        handler.accept(new LlmStreamChunk.TextDelta(config.model()));
        handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
    };

    private static LlmRequest ask(String configName) {
        return LlmRequest.of(configName, List.of(LlmMessage.user("Hi")));
    }

    @Test
    void aConfigGivenToTheBuilderAnswersThroughItsProvider() {
        LlmRuntime llm = LlmRuntime.builder()
                .provider(LlmProvider.OPENAI, echo)
                .config(LlmConfig.openAi("mini", "gpt-5.4-mini", "key"))
                .config(LlmConfig.alias("default", "mini"))
                .build();

        assertThat(llm.chat().chat(ask("default")).text()).isEqualTo("gpt-5.4-mini");
    }

    @Test
    void secretsResolveFromTheProcessEnvironmentByDefault() {
        LlmRuntime llm = LlmRuntime.builder()
                .provider(LlmProvider.OPENAI, echo)
                .config(LlmConfig.openAi("mini", "gpt-5.4-mini", "${MC_LLM_RUNTIME_TEST_UNSET_KEY:from-default}"))
                .build();

        llm.chat().chat(ask("mini"));

        assertThat(seen.get().apiKey()).isEqualTo("from-default");
    }

    @Test
    void anOwnSecretResolverTakesOver() {
        LlmRuntime llm = LlmRuntime.builder()
                .provider(LlmProvider.OPENAI, echo)
                .config(LlmConfig.openAi("mini", "gpt-5.4-mini", "${KEY}"))
                .secrets(config -> config.withApiKey("from-vault"))
                .build();

        llm.chat().chat(ask("mini"));

        assertThat(seen.get().apiKey()).isEqualTo("from-vault");
    }

    @Test
    void configsLandInTheRepositoryGivenAndKeepTheirPlaceholder() {
        InMemoryLlmConfigRepository repository = new InMemoryLlmConfigRepository(LlmScopeSupplier.fixed("tenant"));

        LlmRuntime.builder()
                .configs(repository)
                .config(LlmConfig.openAi("mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"))
                .build();

        assertThat(repository.findByName("mini")).get()
                .extracting(LlmConfig::apiKey).isEqualTo("${OPENAI_API_KEY}");
    }

    @Test
    void embeddingWithoutAGatewaySaysSo() {
        LlmRuntime llm = LlmRuntime.builder().build();

        assertThatThrownBy(llm::embedding).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding gateway");
    }
}
