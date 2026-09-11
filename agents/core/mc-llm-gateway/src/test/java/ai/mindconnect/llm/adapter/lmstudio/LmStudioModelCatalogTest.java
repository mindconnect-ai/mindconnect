package ai.mindconnect.llm.adapter.lmstudio;

import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfigType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog against a fake LM Studio: both listing shapes as a real
 * 0.3.x instance served them, the v1 fallback, and the two ways a server
 * can be absent.
 */
class LmStudioModelCatalogTest {

    private static final String V0 = """
            {"data":[
              {"id":"openai/gpt-oss-120b","object":"model","type":"llm","publisher":"openai",
               "arch":"gpt-oss","compatibility_type":"gguf","quantization":"MXFP4","state":"loaded",
               "max_context_length":131072,"loaded_context_length":32768,"capabilities":["tool_use"]},
              {"id":"text-embedding-nomic-embed-text-v1.5","object":"model","type":"embeddings",
               "publisher":"nomic-ai","arch":"nomic-bert","compatibility_type":"gguf","quantization":"Q4_K_M",
               "state":"loaded","max_context_length":2048,"loaded_context_length":2048},
              {"id":"google/gemma-4-e4b","object":"model","type":"vlm","publisher":"google","arch":"gemma4",
               "compatibility_type":"mlx","quantization":"4bit","state":"not-loaded",
               "max_context_length":131072,"capabilities":["tool_use"]}
            ],"object":"list"}
            """;

    private static final String V1 = """
            {"models":[
              {"type":"llm","publisher":"openai","key":"openai/gpt-oss-120b","display_name":"GPT-OSS 120B",
               "architecture":"gpt-oss","quantization":{"name":"MXFP4","bits_per_weight":4},
               "size_bytes":63387444066,"params_string":"120B",
               "loaded_instances":[{"id":"openai/gpt-oss-120b","config":{"context_length":32768,"parallel":4}}],
               "max_context_length":131072,"format":"gguf",
               "capabilities":{"vision":false,"trained_for_tool_use":true,
                 "reasoning":{"allowed_options":["low","medium","high"],"default":"low"}}},
              {"type":"llm","publisher":"google","key":"google/gemma-4-e4b","display_name":"Gemma 4 E4B",
               "loaded_instances":[],"max_context_length":131072,"format":"mlx",
               "capabilities":{"vision":true,"trained_for_tool_use":true}},
              {"type":"embeddings","publisher":"nomic-ai","key":"text-embedding-nomic-embed-text-v1.5",
               "display_name":"Nomic Embed v1.5","loaded_instances":[],"max_context_length":2048,"format":"gguf"}
            ]}
            """;

    private HttpServer server;
    private final List<String> requestedPaths = new ArrayList<>();
    private LmStudioModelCatalog catalog;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        catalog = new LmStudioModelCatalog(new OkHttpClient(), new ObjectMapper());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** Serves each path with its status and body; everything else is a 500. */
    private void serve(Map<String, Object[]> routes) {
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requestedPaths.add(path);
            Object[] route = routes.get(path);
            int status = route == null ? 500 : (int) route[0];
            byte[] body = route == null || route[1] == null ? new byte[0]
                    : ((String) route[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void readsTheV0Listing() {
        serve(Map.of("/api/v0/models", new Object[]{200, V0}));

        var result = catalog.fetch(baseUrl());

        assertThat(result.available()).isTrue();
        assertThat(result.models()).hasSize(3);

        LmStudioModel gptOss = result.find("openai/gpt-oss-120b");
        assertThat(gptOss.kind()).isEqualTo(LmStudioModel.Kind.LLM);
        assertThat(gptOss.loaded()).isTrue();
        assertThat(gptOss.maxContextLength()).isEqualTo(131072);
        assertThat(gptOss.loadedContextLength()).isEqualTo(32768);
        assertThat(gptOss.effectiveContextLength()).as("loaded wins over max").isEqualTo(32768);
        assertThat(gptOss.capabilities()).containsExactly(LlmCapability.TOOL_CALLING);
        assertThat(gptOss.appliesTo(LlmConfigType.CHAT)).isTrue();
        assertThat(gptOss.appliesTo(LlmConfigType.EMBEDDING)).isFalse();

        LmStudioModel gemma = result.find("google/gemma-4-e4b");
        assertThat(gemma.kind()).isEqualTo(LmStudioModel.Kind.VLM);
        assertThat(gemma.loaded()).isFalse();
        assertThat(gemma.effectiveContextLength()).as("not loaded: the maximum").isEqualTo(131072);
        assertThat(gemma.capabilities()).containsExactlyInAnyOrder(LlmCapability.TOOL_CALLING, LlmCapability.VISION);

        LmStudioModel nomic = result.find("text-embedding-nomic-embed-text-v1.5");
        assertThat(nomic.kind()).isEqualTo(LmStudioModel.Kind.EMBEDDING);
        assertThat(nomic.appliesTo(LlmConfigType.EMBEDDING)).isTrue();
        assertThat(nomic.appliesTo(LlmConfigType.CHAT)).isFalse();
    }

    @Test
    void fallsBackToV1WhenV0IsGone() {
        serve(Map.of(
                "/api/v0/models", new Object[]{404, null},
                "/api/v1/models", new Object[]{200, V1}));

        var result = catalog.fetch(baseUrl());

        assertThat(result.available()).isTrue();
        assertThat(requestedPaths).containsExactly("/api/v0/models", "/api/v1/models");

        LmStudioModel gptOss = result.find("openai/gpt-oss-120b");
        assertThat(gptOss.displayName()).isEqualTo("GPT-OSS 120B");
        assertThat(gptOss.loaded()).isTrue();
        assertThat(gptOss.loadedContextLength()).isEqualTo(32768);
        assertThat(gptOss.maxContextLength()).isEqualTo(131072);
        assertThat(gptOss.toolUse()).isTrue();

        LmStudioModel gemma = result.find("google/gemma-4-e4b");
        assertThat(gemma.kind()).as("v1 says llm + vision, which is a VLM").isEqualTo(LmStudioModel.Kind.VLM);
        assertThat(gemma.loaded()).isFalse();
        assertThat(gemma.loadedContextLength()).isNull();

        assertThat(result.find("text-embedding-nomic-embed-text-v1.5").kind())
                .isEqualTo(LmStudioModel.Kind.EMBEDDING);
    }

    @Test
    void aServerThatIsNotLmStudioIsReportedNotThrown() {
        serve(Map.of());

        var result = catalog.fetch(baseUrl());

        assertThat(result.available()).isFalse();
        assertThat(result.models()).isEmpty();
        assertThat(result.error()).contains("HTTP 500").contains("/api/v0/models").contains("/api/v1/models");
        assertThat(result.find("anything")).isNull();
    }

    @Test
    void nothingListeningIsReportedAfterOneAttempt() {
        String url = baseUrl();
        server.stop(0);

        var result = catalog.fetch(url);

        assertThat(result.available()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(result.baseUrl()).isEqualTo(url);
        assertThat(requestedPaths).isEmpty();
    }

    @Test
    void normalisesTheBaseUrl() {
        assertThat(LmStudioModelCatalog.normalise(null)).isEqualTo("http://localhost:1234");
        assertThat(LmStudioModelCatalog.normalise("  ")).isEqualTo("http://localhost:1234");
        assertThat(LmStudioModelCatalog.normalise("http://box:1234/")).isEqualTo("http://box:1234");
        assertThat(LmStudioModelCatalog.normalise("http://box:1234/v1")).isEqualTo("http://box:1234");
        assertThat(LmStudioModelCatalog.normalise("box:1234")).as("a host without a scheme means http")
                .isEqualTo("http://box:1234");
        assertThat(LmStudioModelCatalog.normalise("HTTPS://box")).isEqualTo("HTTPS://box");
    }

    @Test
    void labelsReadAtAGlance() {
        var loaded = new LmStudioModel("openai/gpt-oss-120b", "GPT-OSS 120B", LmStudioModel.Kind.LLM,
                true, 131072, 32768, true, false);
        assertThat(loaded.label()).isEqualTo("GPT-OSS 120B · 32k loaded (max 128k) · tools");

        var idle = new LmStudioModel("google/gemma-4-e4b", "google/gemma-4-e4b", LmStudioModel.Kind.VLM,
                false, 131072, null, true, false);
        assertThat(idle.label()).isEqualTo("google/gemma-4-e4b · max 128k · tools · vision");

        var embedding = new LmStudioModel("nomic", "nomic", LmStudioModel.Kind.EMBEDDING,
                true, 2048, 2048, false, false);
        assertThat(embedding.label()).isEqualTo("nomic · 2k loaded · embedding");
    }

    @Test
    void halfATypedUrlIsAnUnreachableServer_notAnException() {
        var catalog = new LmStudioModelCatalog(new OkHttpClient(), new ObjectMapper());

        var result = catalog.fetch("dbeise:not a port");

        assertThat(result.available()).isFalse();
        assertThat(result.error()).startsWith("Not a URL: http://dbeise:not a port");
        assertThat(result.models()).isEmpty();
    }
}
