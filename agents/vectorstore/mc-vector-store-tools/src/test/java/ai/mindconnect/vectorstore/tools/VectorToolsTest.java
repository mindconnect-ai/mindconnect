package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knowledge tools against the memory backend with a deterministic fake
 * embedder: upsert replaces per file, search ranks semantically-near texts
 * (here: identical fake vectors) first, delete empties, and the factories
 * report unavailable without the embedding services.
 */
class VectorToolsTest {

    @TempDir
    Path dir;

    /** Maps known texts to fixed vectors so ranking is predictable. */
    private static final LlmEmbeddings FAKE_EMBEDDINGS = (config, texts) -> texts.stream()
            .map(t -> {
                if (t.contains("container")) return new float[]{1f, 0f, 0f};
                if (t.contains("finance")) return new float[]{0f, 1f, 0f};
                return new float[]{0f, 0f, 1f};
            }).toList();

    private static final LlmConfigRepository FAKE_CONFIGS = new LlmConfigRepository() {
        @Override public Optional<LlmConfig> findById(UUID id) { return Optional.empty(); }
        @Override public Optional<LlmConfig> findByName(String name) {
            return "embeddings".equals(name)
                    ? Optional.of(LlmConfig.lmStudio("embeddings", "fake-model", "http://unused"))
                    : Optional.empty();
        }
        @Override public List<LlmConfig> findAll() { return List.of(); }
        @Override public void save(LlmConfig config) { }
        @Override public void deleteById(UUID id) { }
    };

    private ToolEnvironment env;

    @BeforeEach
    void setUp() {
        env = new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) FAKE_EMBEDDINGS);
                if (type == LlmConfigRepository.class) return Optional.of((T) FAKE_CONFIGS);
                return Optional.empty();
            }
            @Override public Optional<String> getString(String key) {
                return switch (key) {
                    case "vectorStoreBackend" -> Optional.of("memory");
                    case "vectorStoreDir" -> Optional.of(dir.toString());
                    default -> Optional.empty();
                };
            }
        };
    }

    private Tool tool(VectorTools.BaseFactory factory) {
        factory.bind(env);
        assertThat(factory.isAvailable()).isTrue();
        return factory.create(null, null);
    }

    @Test
    void upsertSearchDeleteRoundTrip() {
        Tool upsert = tool(new VectorTools.UpsertFactory());
        Tool search = tool(new VectorTools.SearchFactory());
        Tool delete = tool(new VectorTools.DeleteFileFactory());

        String stored = upsert.execute(Map.of("store", "kb", "file_id", "doc1", "chunks", List.of(
                Map.of("text", "podman is a container engine", "title", "Intro"),
                Map.of("text", "the finance report shows growth"))));
        assertThat(stored).contains("Stored 2 chunk(s)");

        String found = search.execute(Map.of("store", "kb", "query", "how to run a container"));
        assertThat(found).contains("podman").contains("Intro").contains("doc1");
        assertThat(found.indexOf("podman")).isLessThan(found.indexOf("finance"));

        // Replace semantics: re-upsert with ONE chunk leaves no stale second chunk.
        upsert.execute(Map.of("store", "kb", "file_id", "doc1", "chunks", List.of(
                Map.of("text", "podman only now"))));
        assertThat(search.execute(Map.of("store", "kb", "query", "container")))
                .doesNotContain("finance");

        assertThat(delete.execute(Map.of("store", "kb", "file_id", "doc1")))
                .contains("Removed");
        assertThat(search.execute(Map.of("store", "kb", "query", "container")))
                .contains("No results");
    }

    @Test
    void factoriesAreUnavailableWithoutEmbeddingServices() {
        var factory = new VectorTools.SearchFactory();
        factory.bind(new ToolEnvironment() {
            @Override public <T> Optional<T> get(Class<T> type) { return Optional.empty(); }
            @Override public Optional<String> getString(String key) { return Optional.empty(); }
        });
        assertThat(factory.isAvailable()).isFalse();
    }

    @Test
    void upsertValidatesChunks() {
        Tool upsert = tool(new VectorTools.UpsertFactory());
        assertThat(upsert.execute(Map.of("store", "kb", "file_id", "d", "chunks", List.of())))
                .startsWith("Error:");
        assertThat(upsert.execute(Map.of("store", "kb", "file_id", "d", "chunks",
                List.of(Map.of("title", "no text")))))
                .startsWith("Error:").contains("text");
    }
}
