package ai.mindconnect.llm.adapter.openai;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Embeddings over the OpenAI-compatible {@code /v1/embeddings} endpoint —
 * the one LM Studio, Ollama (OpenAI mode), OpenAI itself and most gateways
 * speak. Reuses the config's {@code baseUrl}/{@code apiKey} exactly like the
 * chat gateway, including {@code enc:} credential resolution.
 */
public final class OpenAiEmbeddingsGateway implements LlmEmbeddings {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingsGateway.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EncryptionHelper encryption;

    public OpenAiEmbeddingsGateway(OkHttpClient httpClient, ObjectMapper objectMapper,
                                   EncryptionHelper encryption) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.encryption = encryption;
    }

    @Override
    public List<float[]> embed(LlmConfig config, List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        config = config.resolved(encryption);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());
        var input = body.putArray("input");
        texts.forEach(input::add);

        long start = System.currentTimeMillis();
        try {
            Request request = new Request.Builder()
                    .url(config.baseUrl() + "/v1/embeddings")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
            String payload = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Embeddings call failed (" + response.code() + "): "
                        + truncate(payload));
            }
            JsonNode data = objectMapper.readTree(payload).path("data");
            // The API may return entries out of order; "index" is authoritative.
            float[][] vectors = new float[texts.size()][];
            for (JsonNode entry : data) {
                int index = entry.path("index").asInt();
                JsonNode raw = entry.path("embedding");
                float[] vector = new float[raw.size()];
                for (int i = 0; i < raw.size(); i++) {
                    vector[i] = (float) raw.get(i).asDouble();
                }
                vectors[index] = vector;
            }
            List<float[]> result = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                if (vectors[i] == null) {
                    throw new IllegalStateException("Embeddings response is missing index " + i);
                }
                result.add(vectors[i]);
            }
            log.debug("Embedded {} text(s) with {} in {} ms", texts.size(), config.model(),
                    System.currentTimeMillis() - start);
            return result;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Embeddings call to " + config.baseUrl() + " failed: "
                    + e.getMessage(), e);
        }
    }

    private static String truncate(String text) {
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }
}
