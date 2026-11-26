package ai.mindconnect.llm.demo;

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
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.scope.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
 * <p>Run with {@code OPENAI_API_KEY} set; the first argument is the question. Every
 * run is a paid call to OpenAI.
 */
public final class LlmChatDemo {

    private LlmChatDemo() {
    }

    public static void main(String[] args) {
        if (System.getenv("OPENAI_API_KEY") == null || System.getenv("OPENAI_API_KEY").isBlank()) {
            System.err.println("Set OPENAI_API_KEY to run this demo.");
            System.exit(1);
        }
        String question = args.length > 0 ? String.join(" ", args) : "Name three rivers in Germany.";

        // Our providers, one per provider family. Only what is in this map is reachable.
        OkHttpClient http = new OkHttpClient();
        ObjectMapper json = JsonMapper.builder()
                .addModule(new LlmJsonModule()).addModule(new JavaTimeModule()).build();
        Map<LlmProvider, LlmGateway> providers = new EnumMap<>(LlmProvider.class);
        providers.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json));
        providers.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json));
        providers.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json));
        providers.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json));
        providers.put(LlmProvider.LM_STUDIO, new OpenAiCompatibleGateway(http, json));

        // The configs of one namespace. The key stays a placeholder in the store and
        // resolves from the environment on every call.
        LlmConfigRepository configs = new InMemoryLlmConfigRepository(TenantScope.fixed("demo"));
        configs.save(LlmConfig.openAi("openai-mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"));
        configs.save(LlmConfig.alias("agent-default", "openai-mini"));

        LlmChat chat = new RoutingLlmChatService(configs, new DefaultLlmGatewayRegistry(providers),
                LlmConfigResolver::resolved);

        LlmRequest request = LlmRequest.streaming("agent-default", List.of(
                LlmMessage.system("Answer in one short paragraph."),
                LlmMessage.user(question)));
        chat.chatStreaming(request, chunk -> {
            switch (chunk) {
                case LlmStreamChunk.TextDelta delta -> System.out.print(delta.text());
                case LlmStreamChunk.Done done -> System.out.printf("%n[%s, %d tokens in, %d out]%n",
                        done.finishReason(), done.inputTokens(), done.outputTokens());
                default -> { }
            }
        });
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
