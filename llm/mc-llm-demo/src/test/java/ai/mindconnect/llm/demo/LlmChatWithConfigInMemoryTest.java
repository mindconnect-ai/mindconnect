package ai.mindconnect.llm.demo;

import ai.mindconnect.llm.adapter.env.LlmConfigResolver;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.demo.adapter.openaisdk.OpenAiSdkGateway;
import ai.mindconnect.llm.gateway.service.DefaultLlmGatewayRegistry;
import ai.mindconnect.llm.gateway.service.RoutingLlmChatService;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.llm.runtime.api.domain.FinishReason;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.domain.UnknownLlmConfigException;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;
import ai.mindconnect.scope.TenantScope;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The caller's view: an {@link LlmChat} whose configs live in memory, with the
 * provider of one's own behind it. The caller names a config, nothing else; model,
 * endpoint and key come from the stored config. A local server answers like OpenAI.
 */
class LlmChatWithConfigInMemoryTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    private LlmConfigRepository configs;
    private LlmChat chat;

    @BeforeEach
    void wire() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Rhine\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":1,\"total_tokens\":10}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        // The configs of one namespace, in memory.
        configs = new InMemoryLlmConfigRepository(TenantScope.fixed("demo"));
        chat = new RoutingLlmChatService(configs,
                new DefaultLlmGatewayRegistry(Map.of(LlmProvider.OPENAI, new OpenAiSdkGateway())),
                // Placeholders such as ${DEMO_OPENAI_KEY:sk-test} resolve from the process environment.
                LlmConfigResolver::resolved);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void the_caller_names_only_the_config_and_the_stored_config_supplies_model_endpoint_and_key() {
        configs.save(new LlmConfig(LlmConfigId.random(), "agent-default", LlmProvider.OPENAI, "gpt-test",
                "http://127.0.0.1:" + server.getAddress().getPort(), "${DEMO_OPENAI_KEY:sk-test}", 0.2, 256,
                Map.of(), null, false, null, null, null, null, null));
        List<LlmStreamChunk> chunks = new ArrayList<>();

        chat.chatStreaming(LlmRequest.streaming("agent-default", List.of(LlmMessage.user("A river in Germany?"))),
                chunks::add);

        assertThat(chunks).containsExactly(
                new LlmStreamChunk.TextDelta("Rhine"),
                new LlmStreamChunk.Done(FinishReason.STOP, 9, 1));
        assertThat(requestBody.get()).contains("\"model\":\"gpt-test\"", "A river in Germany?");
        assertThat(authorization.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    void a_config_name_the_store_does_not_know_is_refused_before_any_call() {
        assertThatThrownBy(() -> chat.chatStreaming(
                LlmRequest.streaming("nobody-saved-this", List.of(LlmMessage.user("Hi"))), chunk -> { }))
                .isInstanceOf(UnknownLlmConfigException.class);
        assertThat(requestBody.get()).isNull();
    }
}
