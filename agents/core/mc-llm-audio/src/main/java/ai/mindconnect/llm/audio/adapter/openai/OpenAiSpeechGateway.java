package ai.mindconnect.llm.audio.adapter.openai;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.audio.domain.SpeechRequest;
import ai.mindconnect.llm.audio.domain.SpeechResult;
import ai.mindconnect.llm.audio.port.out.SpeechGateway;
import ai.mindconnect.llm.domain.LlmConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text to speech against the OpenAI-compatible endpoint,
 * {@code POST {baseUrl}/v1/audio/speech} with a JSON body, answered with the
 * encoded audio. The voice and the format come from the request, else from
 * the config's additional parameters ({@code voice}, {@code response_format}),
 * else {@code alloy} and {@code mp3}; {@code instructions} and {@code speed}
 * pass through from the config when set.
 */
public final class OpenAiSpeechGateway implements SpeechGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSpeechGateway.class);
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_ERROR_BODY = 500;
    /** A sentence takes a second or two; a minute means the endpoint went quiet. */
    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(1);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionHelper encryption;
    private final EnvVarResolver env;

    /** @param env where {@code ${VAR}} placeholders in a config resolve from */
    public OpenAiSpeechGateway(OkHttpClient httpClient, ObjectMapper objectMapper, EncryptionHelper encryption,
                               EnvVarResolver env) {
        this.httpClient = httpClient.newBuilder().callTimeout(CALL_TIMEOUT).build();
        this.objectMapper = objectMapper;
        this.encryption = encryption;
        this.env = env;
    }

    @Override
    public SpeechResult synthesize(LlmConfig config, SpeechRequest request) {
        LlmConfig cfg = config.resolved(env, encryption);
        String format = firstNonBlank(request.format(), param(cfg, "response_format"), "mp3");
        Request.Builder http = new Request.Builder()
                .url(cfg.baseUrl() + "/v1/audio/speech")
                .post(RequestBody.create(body(cfg, request, format), JSON));
        // A local server usually takes no key; sending an empty bearer breaks some of them.
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            http.header("Authorization", "Bearer " + cfg.apiKey());
        }
        long start = System.currentTimeMillis();
        try (Response response = httpClient.newCall(http.build()).execute()) {
            byte[] payload = response.body() != null ? response.body().bytes() : new byte[0];
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Speech call failed (" + response.code() + "): "
                        + truncate(new String(payload)));
            }
            log.debug("Spoke {} characters with {} in {} ms ({} bytes)", request.text().length(), cfg.model(),
                    System.currentTimeMillis() - start, payload.length);
            return new SpeechResult(payload, contentType(response.header("content-type"), format));
        } catch (IOException e) {
            throw new IllegalStateException("Speech call to " + cfg.baseUrl() + " failed: " + e.getMessage(), e);
        }
    }

    /** The JSON body. Package-private so a test can read it back. */
    String body(LlmConfig cfg, SpeechRequest request, String format) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("model", cfg.model());
        fields.put("input", request.text());
        fields.put("voice", firstNonBlank(request.voice(), param(cfg, "voice"), "alloy"));
        fields.put("response_format", format);
        String instructions = param(cfg, "instructions");
        if (instructions != null) fields.put("instructions", instructions);
        String speed = param(cfg, "speed");
        if (speed != null) fields.put("speed", Double.parseDouble(speed));
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the speech request", e);
        }
    }

    /** The provider's media type when it names an audio one, else the one the format implies. */
    private static String contentType(String header, String format) {
        if (header != null && header.startsWith("audio/")) {
            int semicolon = header.indexOf(';');
            return semicolon < 0 ? header : header.substring(0, semicolon).trim();
        }
        return switch (format) {
            case "opus" -> "audio/ogg";
            case "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            case "pcm" -> "audio/pcm";
            default -> "audio/mpeg";
        };
    }

    private static String param(LlmConfig cfg, String key) {
        Object value = cfg.additionalParams() == null ? null : cfg.additionalParams().get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) return first;
        return second != null ? second : fallback;
    }

    private static String truncate(String text) {
        return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) + "…" : text;
    }
}
