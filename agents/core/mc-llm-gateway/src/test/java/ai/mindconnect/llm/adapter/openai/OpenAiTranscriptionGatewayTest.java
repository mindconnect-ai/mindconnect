package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;
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

/**
 * The OpenAI-compatible transcription adapter: what it puts into the
 * multipart body, how it reads the two answer shapes (JSON and plain text),
 * and that a provider error arrives readable.
 */
class OpenAiTranscriptionGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] AUDIO = "not really audio".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile String responseBody = "{\"text\":\"hello\"}";
    private volatile int responseCode = 200;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
        return LlmConfig.speechToText("whisper", LlmProvider.OPENAI, "whisper-1",
                "http://localhost:" + server.getAddress().getPort(), "test-key");
    }

    private OpenAiTranscriptionGateway gateway() {
        return new OpenAiTranscriptionGateway(new OkHttpClient(), MAPPER, new EncryptionHelper(null));
    }

    private static TranscriptionRequest recording() {
        return TranscriptionRequest.of(AUDIO, "speech.webm", "audio/webm");
    }

    @Test
    void theRecordingTravelsAsAFilePartNamedLikeTheUpload() {
        TranscriptionResult result = gateway().transcribe(config(), recording());

        assertThat(result.text()).isEqualTo("hello");
        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");
        assertThat(lastBody.get())
                .contains("name=\"model\"").contains("whisper-1")
                .contains("name=\"response_format\"").contains("json")
                .contains("name=\"file\"").contains("filename=\"speech.webm\"")
                .contains("not really audio");
    }

    @Test
    void aRequestLanguageWinsOverTheConfigsDefault() {
        LlmConfig withDefaults = new LlmConfig(config().id(), "whisper", LlmProvider.OPENAI,
                "whisper-1", config().baseUrl(), "test-key", 0.0, 0,
                Map.of("language", "en", "prompt", "Mindconnect", "temperature", "0.2"),
                null, false, null, null, null,
                ai.mindconnect.llm.domain.LlmConfigType.SPEECH_TO_TEXT, null);

        Map<String, String> fields = gateway()
                .formFields(withDefaults, recording().withLanguage("de"), "json");

        assertThat(fields).containsEntry("language", "de")
                .containsEntry("prompt", "Mindconnect")
                .containsEntry("temperature", "0.2");
    }

    @Test
    void withoutALanguageAnywhereTheFieldIsOmittedSoTheModelDetectsIt() {
        Map<String, String> fields = gateway().formFields(config(), recording(), "json");

        assertThat(fields).doesNotContainKey("language").doesNotContainKey("prompt");
    }

    @Test
    void verboseJsonCarriesLanguageAndDuration() {
        responseBody = "{\"text\":\"Guten Tag\",\"language\":\"german\",\"duration\":2.5,"
                + "\"usage\":{\"input_tokens\":14,\"output_tokens\":3}}";

        TranscriptionResult result = gateway().transcribe(config(), recording());

        assertThat(result.text()).isEqualTo("Guten Tag");
        assertThat(result.language()).isEqualTo("german");
        assertThat(result.durationSeconds()).isEqualTo(2.5);
        assertThat(result.inputTokens()).isEqualTo(14);
        assertThat(result.outputTokens()).isEqualTo(3);
    }

    @Test
    void aTextResponseFormatIsTakenAsTheTranscriptItself() {
        responseBody = "  Plain spoken words.\n";
        LlmConfig asText = new LlmConfig(config().id(), "whisper", LlmProvider.OPENAI, "whisper-1",
                config().baseUrl(), "test-key", 0.0, 0, Map.of("response_format", "text"),
                null, false, null, null, null,
                ai.mindconnect.llm.domain.LlmConfigType.SPEECH_TO_TEXT, null);

        TranscriptionResult result = gateway().transcribe(asText, recording());

        assertThat(result.text()).isEqualTo("Plain spoken words.");
        assertThat(result.language()).isNull();
    }

    @Test
    void aProviderErrorNamesStatusAndBody() {
        responseCode = 401;
        responseBody = "{\"error\":{\"message\":\"Incorrect API key\"}}";

        assertThatThrownBy(() -> gateway().transcribe(config(), recording()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Incorrect API key");
    }

    @Test
    void aKeylessLocalServerGetsNoAuthorizationHeader() {
        LlmConfig local = LlmConfig.speechToText("local", LlmProvider.OPENAI, "whisper-large-v3",
                config().baseUrl(), null);

        gateway().transcribe(local, recording());

        assertThat(lastAuth.get()).isNull();
    }

    @Test
    void anEmptyRecordingIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> TranscriptionRequest.of(new byte[0], "speech.webm", "audio/webm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audio");
    }
}
