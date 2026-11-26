package ai.mindconnect.llm.demo;

import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.llm.adapter.env.LlmConfigResolver;
import ai.mindconnect.llm.gateway.LlmRuntime;
import ai.mindconnect.llm.jackson.LlmJsonModule;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.providers.standard.StandardProviders;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;

import java.util.List;

/**
 * A chat through the gateway's {@link LlmChat}, assembled by {@link LlmRuntime}'s builder
 * with our providers behind it: the configs live in memory, and the caller names a
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
        // Our providers, one per provider family; the configs live in memory. The key stays a
        // placeholder in the store and resolves on every call, here from mc.env.
        OkHttpClient http = new OkHttpClient();
        ObjectMapper json = new JsonMapperFactory(List.of(new LlmJsonModule(), new JavaTimeModule())).create();
        LlmChat chat = LlmRuntime.builder()
                .providers(new StandardProviders(http, json).chat())
                .config(LlmConfig.openAi("openai-mini", "gpt-5.4-mini", "${OPENAI_API_KEY}"))
                .config(LlmConfig.alias("agent-default", "openai-mini"))
                .secrets(config -> LlmConfigResolver.resolved(config, DemoConsole.environment()))
                .build()
                .chat();

        LlmRequest request = LlmRequest.streaming("agent-default", List.of(
                LlmMessage.system("Answer in one short paragraph."),
                LlmMessage.user(DemoConsole.question(args))));
        chat.chatStreaming(request, DemoConsole::print);
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
