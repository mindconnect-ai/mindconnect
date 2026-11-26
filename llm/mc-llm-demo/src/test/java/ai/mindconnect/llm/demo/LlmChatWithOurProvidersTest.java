package ai.mindconnect.llm.demo;

import ai.mindconnect.llm.adapter.env.LlmConfigResolver;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.gateway.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.gateway.service.RoutingLlmChatService;
import ai.mindconnect.llm.jackson.LlmJsonModule;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.providers.anthropic.ClaudeGateway;
import ai.mindconnect.llm.providers.gemini.GeminiGateway;
import ai.mindconnect.llm.providers.openai.AzureOpenAiGateway;
import ai.mindconnect.llm.providers.openai.OpenAiCompatibleGateway;
import ai.mindconnect.llm.providers.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.runtime.api.domain.FinishReason;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.scope.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Way 1 of the README, as code: our providers behind {@link LlmChat}, the configs in an
 * {@link LlmConfigRepository} in memory. The caller names a config; which provider,
 * model, endpoint and key that means is the store's business. A local server answers
 * like OpenAI's Responses API.
 */
class LlmChatWithOurProvidersTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    private LlmConfigRepository configs;
    private LlmChat chat;

    @BeforeEach
    void wire() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Rhine\",\"output_index\":0}\n\n"
                    + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":9,\"output_tokens\":1}}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        OkHttpClient http = new OkHttpClient();
        ObjectMapper json = JsonMapper.builder()
                .addModule(new LlmJsonModule()).addModule(new JavaTimeModule()).build();
        Map<LlmProvider, LlmGateway> providers = new EnumMap<>(LlmProvider.class);
        providers.put(LlmProvider.OPENAI, new OpenAiResponsesGateway(http, json));
        providers.put(LlmProvider.ANTHROPIC, new ClaudeGateway(http, json));
        providers.put(LlmProvider.AZURE_OPENAI, new AzureOpenAiGateway(http, json));
        providers.put(LlmProvider.GOOGLE_GEMINI, new GeminiGateway(http, json));
        providers.put(LlmProvider.LM_STUDIO, new OpenAiCompatibleGateway(http, json));

        configs = new InMemoryLlmConfigRepository(TenantScope.fixed("demo"));
        chat = new RoutingLlmChatService(configs, new DefaultLlmGatewayRegistry(providers),
                LlmConfigResolver::resolved);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private LlmConfig openAi(String name) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.OPENAI, "gpt-test",
                "http://127.0.0.1:" + server.getAddress().getPort(), "${DEMO_OPENAI_KEY:sk-test}", 0.2, 256,
                Map.of(), null, false, null, null, null, null, null);
    }

    private List<LlmStreamChunk> ask(String configName) {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        chat.chatStreaming(LlmRequest.streaming(configName, List.of(LlmMessage.user("A river in Germany?"))),
                chunks::add);
        return chunks;
    }

    @Test
    void a_config_saved_in_the_repository_is_answered_by_our_openai_provider() {
        configs.save(openAi("agent-default"));

        assertThat(ask("agent-default")).containsExactly(
                new LlmStreamChunk.TextDelta("Rhine"),
                new LlmStreamChunk.Done(FinishReason.STOP, 9, 1));
        assertThat(requestBody.get()).contains("\"model\":\"gpt-test\"", "A river in Germany?");
        assertThat(authorization.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    void an_alias_in_the_repository_lets_the_caller_keep_its_name_while_the_model_behind_it_changes() {
        configs.save(openAi("openai-mini"));
        configs.save(LlmConfig.alias("agent-default", "openai-mini"));

        assertThat(ask("agent-default")).contains(new LlmStreamChunk.TextDelta("Rhine"));
        assertThat(requestBody.get()).contains("\"model\":\"gpt-test\"");
    }
}
