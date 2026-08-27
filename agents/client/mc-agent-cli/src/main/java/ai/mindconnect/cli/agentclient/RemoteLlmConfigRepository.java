package ai.mindconnect.cli.agentclient;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RemoteLlmConfigRepository implements LlmConfigRepository {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public RemoteLlmConfigRepository(String baseUrl, OkHttpClient http, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public void save(LlmConfig config) {
        try {
            String body = mapper.writeValueAsString(config);
            Request req = new Request.Builder()
                    .url(baseUrl + "/api/llm-configs")
                    .post(RequestBody.create(body, JSON)).build();
            try (Response resp = http.newCall(req).execute()) {
                assertOk(resp);
            }
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<LlmConfig> findById(UUID id) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/llm-configs/" + id)
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 404) return Optional.empty();
            assertOk(resp);
            return Optional.of(mapper.readValue(resp.body().string(), LlmConfig.class));
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return findAll().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    @Override
    public List<LlmConfig> findAll() {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/llm-configs")
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
            return mapper.readValue(resp.body().string(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteById(UUID id) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/llm-configs/" + id)
                .delete().build();
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    private void assertOk(Response resp) {
        if (!resp.isSuccessful()) {
            throw new RuntimeException("Server returned " + resp.code());
        }
    }
}
