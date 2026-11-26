package ai.mindconnect.llm.demo;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.llm.adapter.env.LlmConfigResolver;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.gateway.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.gateway.service.RoutingLlmChatService;
import ai.mindconnect.llm.jackson.LlmJsonModule;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.providers.anthropic.ClaudeGateway;
import ai.mindconnect.llm.providers.gemini.GeminiGateway;
import ai.mindconnect.llm.providers.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.providers.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.providers.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A chat through the gateway service's {@link LlmChat}, with our providers behind it:
 * the configs live in an {@link LlmConfigRepository} in memory, and the caller names a
 * config, nothing else. Which
 * provider, model and key that means is the store's business; here an alias
 * {@code agent-default} points at an OpenAI config, so the model behind the name can
 * change without the caller noticing.
 *
 * <p>The key comes from {@code mc.env} or the environment; the first argument is the question. Every
 * run is a paid call to OpenAI.
 */
public final class LlmChatWithOurProvidersDemo {

    private LlmChatWithOurProvidersDemo() {
    }

    public static void main(String[] args) {
        // Our providers, one per provider family. Only what is in this map is reachable.
        OkHttpClient http = new OkHttpClient();
        ObjectMapper json = new JsonMapperFactory(List.of(new LlmJsonModule(), new JavaTimeModule())).create();
        Map<LlmProvider, LlmGateway> providers = new EnumMap<>(LlmProvider.class);
        providers.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json));
        providers.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json));
        providers.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json));
        providers.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json));
        providers.put(LlmProvider.LM_STUDIO, new OpenAiCompatibleGateway(http, json));

        // The configs of one namespace. The key stays a placeholder in the store and
        // resolves on every call, here from mc.env.
        LlmConfigRepository configs = new InMemoryLlmConfigRepository(LlmScopeSupplier.fixed("demo"));
        configs.save(LlmConfig.openAi("openai-mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"));
        configs.save(LlmConfig.alias("agent-default", "openai-mini"));

        LlmChat chat = new RoutingLlmChatService(configs, new DefaultLlmGatewayRegistry(providers),
                config -> LlmConfigResolver.resolved(config, DemoConsole.environment()));

        LlmRequest request = LlmRequest.streaming("agent-default", List.of(
                LlmMessage.system("Answer in one short paragraph."),
                LlmMessage.user(DemoConsole.question(args))));
        chat.chatStreaming(request, DemoConsole::print);
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
