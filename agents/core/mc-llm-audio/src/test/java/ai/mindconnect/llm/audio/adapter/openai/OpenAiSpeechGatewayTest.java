package ai.mindconnect.llm.audio.adapter.openai;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.audio.domain.SpeechRequest;
import ai.mindconnect.llm.audio.domain.SpeechResult;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the adapter sends to the speech endpoint and how it reads the audio and the errors back. */
class OpenAiSpeechGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] AUDIO = {1, 2, 3, 4};

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int responseCode = 200;
    private volatile byte[] responseBody = AUDIO;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/audio/speech", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(responseCode, responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsTextModelAndTheConfiguredVoice() throws Exception {
        SpeechResult result = gateway().synthesize(config(Map.of("voice", "nova", "instructions", "warm")),
                SpeechRequest.of("Hallo Tagebuch"));

        assertThat(result.audio()).containsExactly(AUDIO);
        assertThat(result.contentType()).isEqualTo("audio/mpeg");
        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");
        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini-tts");
        assertThat(body.path("input").asText()).isEqualTo("Hallo Tagebuch");
        assertThat(body.path("voice").asText()).isEqualTo("nova");
        assertThat(body.path("response_format").asText()).isEqualTo("mp3");
        assertThat(body.path("instructions").asText()).isEqualTo("warm");
    }

    @Test
    void theRequestsVoiceAndFormatWinOverTheConfigs() throws Exception {
        gateway().synthesize(config(Map.of("voice", "nova")), new SpeechRequest("Hi", "onyx", "opus"));

        JsonNode body = MAPPER.readTree(lastBody.get());
        assertThat(body.path("voice").asText()).isEqualTo("onyx");
        assertThat(body.path("response_format").asText()).isEqualTo("opus");
    }

    @Test
    void aProviderErrorArrivesReadable() {
        responseCode = 401;
        responseBody = "{\"error\":\"invalid key\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> gateway().synthesize(config(Map.of()), SpeechRequest.of("Hi")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid key");
    }

    private OpenAiSpeechGateway gateway() {
        return new OpenAiSpeechGateway(new OkHttpClient(), MAPPER, new EncryptionHelper(null), EnvVarResolver.system());
    }

    private LlmConfig config(Map<String, Object> params) {
        return new LlmConfig(LlmConfigId.random(), "tts", LlmProvider.OPENAI, "gpt-4o-mini-tts",
                "http://localhost:" + server.getAddress().getPort(), "test-key", 0.0, 0, params, null,
                false, null, null, null, LlmConfigType.TEXT_TO_SPEECH, null);
    }
}
