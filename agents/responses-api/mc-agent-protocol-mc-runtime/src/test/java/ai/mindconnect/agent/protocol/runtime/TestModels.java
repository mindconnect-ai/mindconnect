package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.common.util.McEnv;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Which model the live tests talk to — configured in the repository's
 * {@code mc.env}, so switching between a local server and a hosted provider
 * is a config change, not a code change:
 *
 * <pre>
 * TEST_LLM_BASE_URL=http://localhost:1234        # default
 * TEST_LLM_MODEL=openai/gpt-oss-120b
 * TEST_LLM_API_KEY=none
 * TEST_EMBEDDING_MODEL=text-embedding-nomic-embed-text-v1.5
 * TEST_EMBEDDING_API_KEY=none
 * </pre>
 *
 * <p>Everything is OpenAI-compatible, so the same wiring reaches LM Studio,
 * Ollama or api.openai.com — only the URL, model and key differ.
 */
final class TestModels {

    private TestModels() { }

    static String baseUrl() {
        return McEnv.get("TEST_LLM_BASE_URL", "http://localhost:1234");
    }

    static String chatModel() {
        return McEnv.get("TEST_LLM_MODEL", "openai/gpt-oss-120b");
    }

    static String chatApiKey() {
        return McEnv.get("TEST_LLM_API_KEY", "none");
    }

    static String embeddingModel() {
        return McEnv.get("TEST_EMBEDDING_MODEL", "text-embedding-nomic-embed-text-v1.5");
    }

    static String embeddingApiKey() {
        return McEnv.get("TEST_EMBEDDING_API_KEY", "none");
    }

    /** Only run live tests when the configured server actually answers. */
    static boolean available() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v1/models"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
