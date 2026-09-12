package ai.mindconnect.llm.adapter;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a provider which models it serves, so a config can be pointed at one by
 * picking rather than by typing an id correctly from memory.
 *
 * <p>Three request shapes cover every provider that can be asked:
 * <ul>
 *   <li><b>OpenAI-compatible</b> — {@code GET {base}/v1/models} with a bearer
 *       token, answering {@code {"data":[{"id":…}]}}. OpenAI itself, Groq,
 *       Mistral, DeepSeek, Together, OpenRouter, Fireworks, Perplexity and
 *       Ollama all speak it (those that don't simply answer an error, which
 *       the form shows as a hint).</li>
 *   <li><b>Anthropic</b> — the same path, but authenticated with
 *       {@code x-api-key} plus {@code anthropic-version}, and with a
 *       {@code display_name} per model.</li>
 *   <li><b>Gemini</b> — {@code GET {base}/v1beta/models?key=…}, answering
 *       {@code {"models":[{"name":"models/…"}]}} with the generation methods
 *       each model supports, which is what tells chat from embedding.</li>
 * </ul>
 *
 * <p>LM Studio has a catalog of its own ({@code LmStudioModelCatalog}): its
 * native API reports context lengths and capabilities that {@code /v1/models}
 * does not. Azure OpenAI cannot be asked at all — a config there names a
 * <em>deployment</em>, which is the customer's own name for a model.
 *
 * <p>Never throws: an unreachable host, a wrong key or an endpoint that does
 * not exist comes back as a {@link Catalog} carrying the reason, and the form
 * falls back to a text field. Timeouts are short because the call sits inside a
 * form round-trip, and answers are cached briefly so that typing in the form
 * does not re-ask the provider on every keystroke.
 */
public class ProviderModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProviderModelCatalog.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    /** How long an answer is reused. Long enough for one form session, short enough to stay honest. */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public ProviderModelCatalog(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .callTimeout(READ_TIMEOUT.plus(CONNECT_TIMEOUT))
                .build();
        this.objectMapper = objectMapper;
    }

    public ProviderModelCatalog() {
        this(new OkHttpClient(), new ObjectMapper());
    }

    /**
     * One model a provider offers.
     *
     * @param id                  what goes into a config's {@code model}
     * @param label               what the dropdown shows
     * @param type                the config type it fits, or {@code null} when the
     *                            listing does not say — such a model is offered for
     *                            every type, since guessing it away would hide it
     * @param contextWindowTokens the input window the listing reports, or {@code null}
     */
    public record Model(String id, String label, LlmConfigType type, Integer contextWindowTokens) {

        public static Model of(String id, String label, LlmConfigType type) {
            return new Model(id, label, type, null);
        }

        /** Does this model fit a config of the given type? An unclassified model fits any. */
        public boolean appliesTo(LlmConfigType configType) {
            return type == null || type == configType;
        }
    }

    /**
     * What one provider answered. Either {@link #models()} is filled or
     * {@link #error()} says why not — a provider that cannot be asked is an
     * expected outcome, not a failure of the form.
     */
    public record Catalog(String baseUrl, List<Model> models, String error) {

        public boolean available() {
            return error == null;
        }

        /** An answer that could not be had, with the reason to show in the form. */
        public static Catalog unavailable(String baseUrl, String error) {
            return new Catalog(baseUrl, List.of(), error);
        }

        /** The models that fit a config of this type, in the order the provider listed them. */
        public List<Model> forType(LlmConfigType type) {
            return models.stream().filter(m -> m.appliesTo(type)).toList();
        }

        /** The listed model with this id, or {@code null}. */
        public Model find(String id) {
            if (id == null) return null;
            return models.stream().filter(m -> id.equals(m.id())).findFirst().orElse(null);
        }
    }

    /**
     * Can this provider be asked for its models at all? False for LM Studio,
     * which has a richer catalog of its own, and for Azure OpenAI, whose
     * configs name deployments rather than models.
     */
    public static boolean supports(LlmProvider provider) {
        return provider != null
                && provider != LlmProvider.LM_STUDIO
                && provider != LlmProvider.AZURE_OPENAI;
    }

    /**
     * Does a listing at this provider need an API key to answer? Local servers
     * do not, and OpenRouter publishes its catalog to anyone.
     */
    public static boolean needsApiKey(LlmProvider provider) {
        return provider != LlmProvider.OLLAMA && provider != LlmProvider.OPENROUTER;
    }

    /**
     * The models {@code config}'s provider offers. Pass a config whose
     * {@code apiKey} is already resolved ({@link LlmConfig#resolved}) — this
     * sends it as it stands.
     */
    public Catalog fetch(LlmConfig config) {
        if (config == null || !supports(config.provider())) {
            return Catalog.unavailable(null, "This provider does not publish a model list");
        }
        LlmProvider provider = config.provider();
        String baseUrl = normalise(provider.baseUrlOr(config.baseUrl()));
        String apiKey = config.apiKey();
        if (needsApiKey(provider) && (apiKey == null || apiKey.isBlank())) {
            return Catalog.unavailable(baseUrl, "no API key yet");
        }

        String cacheKey = provider.name() + "|" + baseUrl + "|"
                + Integer.toHexString(Objects.hashCode(apiKey));
        Cached cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) return cached.catalog();

        Catalog catalog = ask(provider, baseUrl, apiKey);
        cache.put(cacheKey, new Cached(catalog, System.currentTimeMillis() + CACHE_TTL.toMillis()));
        return catalog;
    }

    private Catalog ask(LlmProvider provider, String baseUrl, String apiKey) {
        try {
            return switch (provider) {
                case ANTHROPIC -> new Catalog(baseUrl,
                        parseAnthropic(get(anthropicRequest(baseUrl, apiKey))), null);
                case GOOGLE_GEMINI -> new Catalog(baseUrl,
                        parseGemini(get(geminiRequest(baseUrl, apiKey))), null);
                default -> new Catalog(baseUrl,
                        parseOpenAi(get(openAiRequest(baseUrl, apiKey))), null);
            };
        } catch (IOException e) {
            log.debug("Model listing for {} at {} failed: {}", provider, baseUrl, e.getMessage());
            return Catalog.unavailable(baseUrl, e.getMessage());
        }
    }

    private static Request openAiRequest(String baseUrl, String apiKey) {
        Request.Builder request = new Request.Builder().url(baseUrl + "/v1/models").get();
        if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        return request.build();
    }

    private static Request anthropicRequest(String baseUrl, String apiKey) {
        return new Request.Builder()
                .url(baseUrl + "/v1/models?limit=1000")
                .header("x-api-key", apiKey == null ? "" : apiKey)
                .header("anthropic-version", "2023-06-01")
                .get()
                .build();
    }

    private static Request geminiRequest(String baseUrl, String apiKey) {
        return new Request.Builder()
                .url(baseUrl + "/v1beta/models?pageSize=200&key=" + (apiKey == null ? "" : apiKey))
                .get()
                .build();
    }

    /** {@code {"data":[{"id":"gpt-4o"}]}} — the shape every OpenAI-compatible API answers. */
    List<Model> parseOpenAi(JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        if (!data.isArray()) throw new IOException("Unexpected /v1/models answer: no 'data' array");
        List<Model> models = new ArrayList<>();
        for (JsonNode m : data) {
            String id = m.path("id").asText(null);
            if (id == null || id.isBlank() || fitsNoConfigType(id)) continue;
            // OpenRouter is the one that says more: a display name and the window.
            String name = m.path("name").asText(null);
            Integer context = intOrNull(m.path("context_length"));
            if (context == null) context = intOrNull(m.path("context_window"));
            models.add(new Model(id, label(id, name, context), typeOf(id), context));
        }
        models.sort(java.util.Comparator.comparing(Model::id));
        return models;
    }

    /** {@code {"data":[{"id":…,"display_name":…}]}} — Anthropic lists chat models only. */
    List<Model> parseAnthropic(JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        if (!data.isArray()) throw new IOException("Unexpected /v1/models answer: no 'data' array");
        List<Model> models = new ArrayList<>();
        for (JsonNode m : data) {
            String id = m.path("id").asText(null);
            if (id == null || id.isBlank()) continue;
            models.add(Model.of(id, label(id, m.path("display_name").asText(null), null),
                    LlmConfigType.CHAT));
        }
        return models;
    }

    /**
     * {@code {"models":[{"name":"models/gemini-2.0-flash",…}]}}. The id a config
     * needs is the name without its {@code models/} prefix, and
     * {@code supportedGenerationMethods} is what separates a chat model from an
     * embedding one.
     */
    List<Model> parseGemini(JsonNode root) throws IOException {
        JsonNode data = root.path("models");
        if (!data.isArray()) throw new IOException("Unexpected /v1beta/models answer: no 'models' array");
        List<Model> models = new ArrayList<>();
        for (JsonNode m : data) {
            String name = m.path("name").asText(null);
            if (name == null || name.isBlank()) continue;
            String id = name.startsWith("models/") ? name.substring("models/".length()) : name;
            if (fitsNoConfigType(id)) continue;
            boolean chat = false;
            boolean embedding = false;
            for (JsonNode method : m.path("supportedGenerationMethods")) {
                String value = method.asText("");
                chat |= "generateContent".equals(value) || "streamGenerateContent".equals(value);
                embedding |= "embedContent".equals(value) || "embedText".equals(value);
            }
            LlmConfigType type = chat ? LlmConfigType.CHAT : embedding ? LlmConfigType.EMBEDDING : null;
            Integer context = intOrNull(m.path("inputTokenLimit"));
            models.add(new Model(id, label(id, m.path("displayName").asText(null), context),
                    type, context));
        }
        return models;
    }

    /**
     * What an id says about the model's job. Only the unmistakable cases are
     * classified — anything else stays {@code null} and is offered for every
     * config type, because a listing that says nothing is no reason to hide a
     * model from the admin who knows better.
     */
    static LlmConfigType typeOf(String id) {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("embed")) return LlmConfigType.EMBEDDING;
        if (lower.contains("whisper") || lower.contains("transcribe")) return LlmConfigType.SPEECH_TO_TEXT;
        return null;
    }

    /**
     * Ids of models no config can use: image and video generators, text-to-speech,
     * moderation, realtime and computer-use models, and the legacy completion
     * models that predate chat. Providers list them next to their chat models,
     * but a chat call to one fails, and there is no config type for what they do.
     */
    private static final java.util.regex.Pattern NO_CONFIG_TYPE = java.util.regex.Pattern.compile(
            "dall-e|gpt-image|chatgpt-image|imagen|(^|/)veo-|(^|/)sora"
                    + "|(^|[-_/.])tts([-_.]|$)"
                    + "|moderation|realtime|computer-use"
                    + "|(^|/)(babbage|davinci)-\\d+$|(^|/)gpt-3\\.5-turbo-instruct");

    /** Is this a model none of the config types can call? See {@link #NO_CONFIG_TYPE}. */
    static boolean fitsNoConfigType(String id) {
        return NO_CONFIG_TYPE.matcher(id.toLowerCase(java.util.Locale.ROOT)).find();
    }

    /** {@code gpt-4o · GPT-4o · 128k} — id first, since that is what the config stores. */
    static String label(String id, String displayName, Integer contextTokens) {
        StringBuilder label = new StringBuilder(id);
        if (displayName != null && !displayName.isBlank() && !displayName.equals(id)) {
            label.append(" · ").append(displayName);
        }
        if (contextTokens != null && contextTokens > 0) {
            label.append(" · ").append(contextTokens >= 1000 ? (contextTokens / 1000) + "k" : contextTokens);
        }
        return label.toString();
    }

    /** Strips a trailing slash and an accidental {@code /v1}, which the adapters append themselves. */
    static String normalise(String baseUrl) {
        if (baseUrl == null) return null;
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return base;
    }

    private JsonNode get(Request request) throws IOException {
        Response response;
        try {
            response = httpClient.newCall(request).execute();
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException(e.getMessage() == null
                    ? request.url() + " unreachable" : e.getMessage(), e);
        }
        try (response) {
            if (!response.isSuccessful()) {
                throw new IOException(switch (response.code()) {
                    case 401, 403 -> "HTTP " + response.code() + " — the API key is not accepted";
                    case 404 -> "HTTP 404 — this endpoint serves no model list";
                    default -> "HTTP " + response.code() + " from " + request.url();
                });
            }
            if (response.body() == null) throw new IOException("Empty answer from " + request.url());
            return objectMapper.readTree(response.body().byteStream());
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private record Cached(Catalog catalog, long expiresAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }
}
