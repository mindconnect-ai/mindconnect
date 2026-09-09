package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.TranscriptionRequest;
import ai.mindconnect.llm.domain.TranscriptionResult;
import ai.mindconnect.llm.port.out.TranscriptionGateway;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Speech-to-text against the OpenAI-compatible transcription endpoint,
 * {@code POST {baseUrl}/v1/audio/transcriptions} as {@code multipart/form-data}.
 * Works for OpenAI's own Whisper and transcribe models, for Groq, and for a
 * local faster-whisper server — the base URL decides.
 *
 * <p>The audio is forwarded as the uploaded file's bytes; the provider sniffs
 * the container from the file name, which is why
 * {@link TranscriptionRequest#filename()} carries an extension.
 *
 * <p>Extras travel in {@link LlmConfig#additionalParams()}: {@code language},
 * {@code prompt}, {@code temperature} and {@code response_format}. Values on
 * the request win over the config's.
 */
public final class OpenAiTranscriptionGateway implements TranscriptionGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTranscriptionGateway.class);
    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");
    private static final int MAX_ERROR_BODY = 500;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionHelper encryption;

    public OpenAiTranscriptionGateway(OkHttpClient httpClient, ObjectMapper objectMapper,
                                      EncryptionHelper encryption) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.encryption = encryption;
    }

    @Override
    public TranscriptionResult transcribe(LlmConfig config, TranscriptionRequest request) {
        return transcribe(config, request, null);
    }

    /**
     * Transcribes, and hands over the text as it forms when the model can do
     * that. Asking costs nothing: the {@code stream} field is added only when
     * somebody is listening, a model that cannot stream ignores it and
     * answers with plain JSON, and the answer's content type says which of
     * the two arrived. A config can opt out with {@code stream=off} for a
     * server that refuses fields it does not know.
     */
    @Override
    public TranscriptionResult transcribe(LlmConfig config, TranscriptionRequest request,
                                          Consumer<String> onDelta) {
        LlmConfig cfg = config.resolved(encryption);
        String responseFormat = param(cfg, "response_format", "json");
        String stream = param(cfg, "stream", "auto");
        boolean askForStream = onDelta != null
                && !"off".equalsIgnoreCase(stream) && !"false".equalsIgnoreCase(stream);

        long start = System.currentTimeMillis();
        Request.Builder http = new Request.Builder()
                .url(cfg.baseUrl() + "/v1/audio/transcriptions")
                .post(multipartBody(cfg, request, responseFormat, askForStream));
        // A local server usually takes no key; sending an empty bearer breaks some of them.
        if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            http.header("Authorization", "Bearer " + cfg.apiKey());
        }

        try (Response response = httpClient.newCall(http.build()).execute()) {
            if (!response.isSuccessful()) {
                String payload = response.body() != null ? response.body().string() : "";
                throw new IllegalStateException("Transcription call failed (" + response.code()
                        + "): " + truncate(payload));
            }
            TranscriptionResult result = streamed(response)
                    ? readStream(response, onDelta)
                    : readWhole(response, responseFormat, onDelta);
            log.debug("Transcribed {} bytes with {} in {} ms ({} characters{})",
                    request.audio().length, cfg.model(), System.currentTimeMillis() - start,
                    result.text().length(), streamed(response) ? ", streamed" : "");
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Transcription call to " + cfg.baseUrl() + " failed: "
                    + e.getMessage(), e);
        }
    }

    /** The request body. Package-private so a test can read the parts back. */
    MultipartBody multipartBody(LlmConfig cfg, TranscriptionRequest request, String responseFormat,
                                boolean askForStream) {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        Map<String, String> fields = formFields(cfg, request, responseFormat);
        if (askForStream) fields.put("stream", "true");
        fields.forEach(builder::addFormDataPart);
        MediaType mediaType = request.contentType() == null ? OCTET_STREAM
                : MediaType.parse(request.contentType());
        builder.addFormDataPart("file", request.filename(),
                RequestBody.create(request.audio(), mediaType == null ? OCTET_STREAM : mediaType));
        return builder.build();
    }

    /**
     * The non-file form fields, in a stable order. The request's language and
     * prompt win over the config's defaults; both are omitted when neither
     * says anything, which lets the model detect the language itself.
     */
    Map<String, String> formFields(LlmConfig cfg, TranscriptionRequest request, String responseFormat) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", cfg.model());
        fields.put("response_format", responseFormat);

        String language = firstNonBlank(request.language(), param(cfg, "language", null));
        if (language != null) fields.put("language", language);

        String prompt = firstNonBlank(request.prompt(), param(cfg, "prompt", null));
        if (prompt != null) fields.put("prompt", prompt);

        String temperature = param(cfg, "temperature", null);
        if (temperature != null) fields.put("temperature", temperature);

        return fields;
    }

    /** Reads a string from the config's additional parameters, or {@code fallback}. */
    private static String param(LlmConfig cfg, String key, String fallback) {
        Object value = cfg.additionalParams() == null ? null : cfg.additionalParams().get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second != null && !second.isBlank() ? second : null;
    }

    /** Did the provider answer with an event stream rather than one document? */
    private static boolean streamed(Response response) {
        String type = response.header("content-type");
        return type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream");
    }

    /**
     * One document: parsed as before, and handed to a listening caller in a
     * single piece so its handling does not depend on the model.
     */
    private TranscriptionResult readWhole(Response response, String responseFormat,
                                          Consumer<String> onDelta) throws IOException {
        String payload = response.body() != null ? response.body().string() : "";
        TranscriptionResult result = parse(payload, responseFormat);
        if (onDelta != null && result.text() != null && !result.text().isEmpty()) {
            onDelta.accept(result.text());
        }
        return result;
    }

    /**
     * The event stream: {@code transcript.text.delta} frames as the words
     * form, {@code transcript.text.done} with the whole text and the usage.
     * The done frame is the authority; the deltas are what a caller shows
     * while waiting for it.
     */
    private TranscriptionResult readStream(Response response, Consumer<String> onDelta)
            throws IOException {
        StringBuilder assembled = new StringBuilder();
        TranscriptionResult done = null;
        try (BufferedReader reader = new BufferedReader(response.body().charStream())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JsonNode frame = objectMapper.readTree(data);
                String type = frame.path("type").asText("");
                if ("transcript.text.delta".equals(type)) {
                    String delta = frame.path("delta").asText("");
                    if (delta.isEmpty()) continue;
                    assembled.append(delta);
                    if (onDelta != null) onDelta.accept(delta);
                } else if ("transcript.text.done".equals(type)) {
                    JsonNode usage = frame.path("usage");
                    done = new TranscriptionResult(frame.path("text").asText(""), null, null,
                            usage.path("input_tokens").asInt(0),
                            usage.path("output_tokens").asInt(0));
                }
            }
        }
        // No done frame — the stream broke off. What arrived is still a
        // transcript, and saying so beats throwing away the words.
        return done != null ? done : TranscriptionResult.of(assembled.toString());
    }

    private TranscriptionResult parse(String payload, String responseFormat) {
        // text / srt / vtt answer with a bare body, not with JSON.
        if (!"json".equals(responseFormat) && !"verbose_json".equals(responseFormat)) {
            return TranscriptionResult.of(payload.trim());
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            String text = root.path("text").asText("");
            String language = root.hasNonNull("language") ? root.path("language").asText() : null;
            Double duration = root.hasNonNull("duration") ? root.path("duration").asDouble() : null;
            JsonNode usage = root.path("usage");
            return new TranscriptionResult(text, language, duration,
                    usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0));
        } catch (IOException e) {
            log.warn("Could not parse the transcription response as JSON; using the raw body");
            return TranscriptionResult.of(payload.trim());
        }
    }

    private static String truncate(String text) {
        return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) + "…" : text;
    }
}
