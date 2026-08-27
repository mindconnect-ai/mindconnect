package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenAI-compatible embeddings adapter: request shape, order restoration
 * via the response's index field, and readable failure on non-2xx.
 */
class OpenAiEmbeddingsGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private volatile String responseBody;
    private volatile int responseCode = 200;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            lastRequest.set(MAPPER.readTree(exchange.getRequestBody()));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private LlmConfig config() {
        return LlmConfig.lmStudio("embed", "nomic-embed-text",
                "http://localhost:" + server.getAddress().getPort());
    }

    private OpenAiEmbeddingsGateway gateway() {
        return new OpenAiEmbeddingsGateway(new OkHttpClient(), MAPPER, new EncryptionHelper(null));
    }

    @Test
    void sendsModelAndInputAndRestoresOrderByIndex() {
        // Deliberately out of order — index decides, not array position.
        responseBody = """
                {"data": [
                  {"index": 1, "embedding": [0.0, 1.0]},
                  {"index": 0, "embedding": [1.0, 0.0]}
                ]}""";

        List<float[]> vectors = gateway().embed(config(), List.of("first", "second"));

        assertThat(lastRequest.get().path("model").asText()).isEqualTo("nomic-embed-text");
        assertThat(lastRequest.get().path("input")).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1.0f, 0.0f);
        assertThat(vectors.get(1)).containsExactly(0.0f, 1.0f);
    }

    @Test
    void failsReadablyOnHttpError() {
        responseCode = 500;
        responseBody = "{\"error\": \"model not loaded\"}";

        assertThatThrownBy(() -> gateway().embed(config(), List.of("text")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("model not loaded");
    }

    @Test
    void emptyInputShortCircuitsWithoutHttpCall() {
        responseBody = "{}";
        assertThat(gateway().embed(config(), List.of())).isEmpty();
        assertThat(lastRequest.get()).isNull();
    }
}
