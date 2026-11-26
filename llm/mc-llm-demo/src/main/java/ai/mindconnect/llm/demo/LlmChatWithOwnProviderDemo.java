package ai.mindconnect.llm.demo;

import ai.mindconnect.llm.adapter.env.LlmConfigResolver;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.demo.adapter.openaisdk.OpenAiSdkGateway;
import ai.mindconnect.llm.gateway.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.gateway.service.RoutingLlmChatService;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.llm.management.api.port.out.LlmScopeSupplier;

import java.util.List;
import java.util.Map;

/**
 * A chat through the gateway service's {@link LlmChat}, with a provider of one's own
 * behind it: the SDK-based {@link OpenAiSdkGateway} takes the place of our OpenAI
 * provider, and the caller notices nothing. The configs live in an
 * {@link LlmConfigRepository} in memory; the caller names a config, nothing else.
 *
 * <p>The key comes from {@code mc.env} or the environment; the first argument is the question. Every
 * run is a paid call to OpenAI.
 */
public final class LlmChatWithOwnProviderDemo {

    private LlmChatWithOwnProviderDemo() {
    }

    public static void main(String[] args) {
        // The configs of one namespace. The key stays a placeholder in the store and
        // resolves on every call, here from mc.env.
        LlmConfigRepository configs = new InMemoryLlmConfigRepository(LlmScopeSupplier.fixed("demo"));
        configs.save(LlmConfig.openAi("openai-mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"));
        configs.save(LlmConfig.alias("agent-default", "openai-mini"));

        LlmChat chat = new RoutingLlmChatService(configs,
                new DefaultLlmGatewayRegistry(Map.of(LlmProvider.OPENAI, new OpenAiSdkGateway())),
                config -> LlmConfigResolver.resolved(config, DemoConsole.environment()));

        chat.chatStreaming(LlmRequest.streaming("agent-default", List.of(
                LlmMessage.system("Answer in one short paragraph."),
                LlmMessage.user(DemoConsole.question(args)))), DemoConsole::print);
    }
}
