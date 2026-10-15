package ai.mindconnect.llm.adapter.lmstudio;

import ai.mindconnect.llm.adapter.ModelListing;
import ai.mindconnect.llm.adapter.ModelListingReader;
import ai.mindconnect.llm.domain.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the models installed in an LM Studio instance from its native REST
 * API, so a config can be pointed at one by picking rather than typing, with
 * the context length taken from what LM Studio reports.
 *
 * <p>LM Studio serves three model listings. The OpenAI-compatible
 * {@code /v1/models} only has ids, so it is of no use here. The native
 * {@code /api/v0/models} (every 0.3.x) carries {@code max_context_length} and,
 * for loaded models, {@code loaded_context_length}; the newer
 * {@code /api/v1/models} carries the same under different names plus a display
 * name and richer capabilities. This client asks v0 first and falls back to v1,
 * and folds both shapes onto {@link LmStudioModel}.
 *
 * <p>Timeouts are short on purpose — the call sits in a form round-trip and
 * "LM Studio is not running" must come back as a hint, not a hang.
 */
public class LmStudioModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(LmStudioModelCatalog.class);

    /** Where LM Studio serves by default. */
    public static final String DEFAULT_BASE_URL = LlmProvider.LM_STUDIO.defaultBaseUrl();

    private final ModelListingReader reader;

    public LmStudioModelCatalog(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.reader = new ModelListingReader(httpClient, objectMapper);
    }

    /**
     * Lists the models of the LM Studio instance at {@code baseUrl} (blank
     * means {@link #DEFAULT_BASE_URL}). Never throws: a server that is down,
     * too old, or answering something else yields a {@link ModelListing} with an
     * error message. A server that cannot be reached at all is reported after
     * the first attempt; only an answer that is not the v0 listing (404 from a
     * newer LM Studio, an unexpected body) makes it try v1.
     */
    public ModelListing<LmStudioModel> fetch(String baseUrl) {
        String base = normalise(baseUrl);
        String v0Error;
        try {
            return new ModelListing<>(base, parseV0(get(base + "/api/v0/models")), null);
        } catch (ModelListingReader.Unreachable e) {
            return new ModelListing<>(base, List.of(), e.getMessage());
        } catch (IOException e) {
            v0Error = e.getMessage();
            log.debug("LM Studio /api/v0/models at {} failed ({}), trying /api/v1/models", base, v0Error);
        }
        try {
            return new ModelListing<>(base, parseV1(get(base + "/api/v1/models")), null);
        } catch (IOException e) {
            log.debug("LM Studio /api/v1/models at {} failed ({})", base, e.getMessage());
            return new ModelListing<>(base, List.of(), v0Error + "; " + e.getMessage());
        }
    }

    static String normalise(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        // A host typed without a scheme — "my-mac:1234" — means http; LM Studio speaks nothing else locally.
        if (!base.matches("(?i)https?://.*")) base = "http://" + base;
        return ModelListingReader.withoutApiPath(base);
    }

    private JsonNode get(String url) throws IOException {
        Request request;
        try {
            request = new Request.Builder().url(url).get().build();
        } catch (IllegalArgumentException e) {
            // Half a URL, typed into the form as we speak: no server there yet.
            throw new ModelListingReader.Unreachable("Not a URL: " + url, e);
        }
        return reader.read(request);
    }

    /** {@code /api/v0/models}: flat objects with {@code id}, {@code type}, {@code state}. */
    List<LmStudioModel> parseV0(JsonNode root) throws IOException {
        JsonNode data = root.path("data");
        if (!data.isArray()) throw new IOException("Unexpected /api/v0/models shape: no 'data' array");
        List<LmStudioModel> models = new ArrayList<>();
        for (JsonNode m : data) {
            String id = m.path("id").asText(null);
            if (id == null) continue;
            boolean toolUse = false;
            for (JsonNode c : m.path("capabilities")) toolUse |= "tool_use".equals(c.asText());
            LmStudioModel.Kind kind = LmStudioModel.Kind.parse(m.path("type").asText(null));
            models.add(new LmStudioModel(
                    id, id, kind,
                    "loaded".equals(m.path("state").asText("")),
                    ModelListingReader.intOrNull(m.path("max_context_length")),
                    ModelListingReader.intOrNull(m.path("loaded_context_length")),
                    toolUse,
                    kind == LmStudioModel.Kind.VLM));
        }
        return models;
    }

    /** {@code /api/v1/models}: {@code models[]} with {@code key}, {@code loaded_instances[]}. */
    List<LmStudioModel> parseV1(JsonNode root) throws IOException {
        JsonNode data = root.path("models");
        if (!data.isArray()) throw new IOException("Unexpected /api/v1/models shape: no 'models' array");
        List<LmStudioModel> models = new ArrayList<>();
        for (JsonNode m : data) {
            String id = m.path("key").asText(null);
            if (id == null) continue;
            JsonNode caps = m.path("capabilities");
            boolean vision = caps.path("vision").asBoolean(false);
            Integer loadedContext = null;
            for (JsonNode inst : m.path("loaded_instances")) {
                Integer ctx = ModelListingReader.intOrNull(inst.path("config").path("context_length"));
                if (ctx != null && (loadedContext == null || ctx > loadedContext)) loadedContext = ctx;
            }
            boolean loaded = m.path("loaded_instances").size() > 0;
            String type = m.path("type").asText(null);
            LmStudioModel.Kind kind = LmStudioModel.Kind.parse(type);
            // v1 reports vision models as plain "llm" with capabilities.vision = true.
            if (kind == LmStudioModel.Kind.LLM && vision) kind = LmStudioModel.Kind.VLM;
            models.add(new LmStudioModel(
                    id,
                    m.path("display_name").asText(id),
                    kind,
                    loaded,
                    ModelListingReader.intOrNull(m.path("max_context_length")),
                    loadedContext,
                    caps.path("trained_for_tool_use").asBoolean(false),
                    vision));
        }
        return models;
    }

}
