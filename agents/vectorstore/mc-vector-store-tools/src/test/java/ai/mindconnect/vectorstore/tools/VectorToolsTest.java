package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.empty(); }
        @Override public Optional<LlmConfig> findByName(String name) {
            return "embeddings".equals(name)
                    ? Optional.of(LlmConfig.lmStudio("embeddings", "fake-model", "http://unused"))
                    : Optional.empty();
        }
        @Override public List<LlmConfig> findAll() { return List.of(); }
        @Override public void save(LlmConfig config) { }
        @Override public void deleteById(LlmConfigId id) { }
    };

    private ToolEnvironment env;

    @BeforeEach
    void setUp() {
        env = new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) FAKE_EMBEDDINGS);
                if (type == LlmConfigRepository.class) return Optional.of((T) FAKE_CONFIGS);
                if (type == Namespace.class) return Optional.of((T) new Namespace("test"));
                return Optional.empty();
            }
            @Override public Optional<String> getString(String key) {
                return switch (key) {
                    case "vectorStoreBackend" -> Optional.of("memory");
                    case "dataBaseDir" -> Optional.of(dir.toString());
                    default -> Optional.empty();
                };
            }
        };
    }

    private Tool tool(VectorTools.BaseFactory factory) {
        return tool(factory, null);
    }

    private Tool tool(VectorTools.BaseFactory factory, ToolCallScope scope) {
        factory.bind(env);
        assertThat(factory.isAvailable()).isTrue();
        return factory.create(null, scope);
    }

    private static ToolCallScope session(SessionId sessionId) {
        return new ToolCallScope(UserId.of("alice"), sessionId, null);
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

    /**
     * The upload store of a chat is that chat's alone. Named from inside
     * another session — search, upsert or delete — the store is refused; the
     * own session reaches it by omitting {@code store} or naming it. A store
     * named like a session that does not exist yet is refused too, so the
     * model cannot squat on a foreign session's name. The upload pipeline runs
     * its tools without a session and keeps filling any session's store.
     */
    @Test
    void sessionUploadStoresAreNotReachableFromOtherSessions() {
        SessionId mine = SessionId.random();
        SessionId theirs = SessionId.random();
        String theirStore = "session-" + theirs.value();

        // Their session's store and a session-scoped store under a free name.
        Tool theirUpsert = tool(new VectorTools.UpsertFactory(), session(theirs));
        assertThat(theirUpsert.execute(Map.of("store", theirStore, "scope", "session",
                "file_id", "secret.pdf", "chunks", List.of(Map.of("text", "the finance report")))))
                .contains("Stored 1 chunk(s)");
        assertThat(theirUpsert.execute(Map.of("store", "their-notes", "scope", "session",
                "file_id", "notes", "chunks", List.of(Map.of("text", "finance notes")))))
                .contains("Stored 1 chunk(s)");

        // The ingestion workflow resolves its tools detached — no session, no restriction.
        assertThat(tool(new VectorTools.UpsertFactory()).execute(Map.of("store", theirStore,
                "file_id", "second.pdf", "chunks", List.of(Map.of("text", "finance appendix")))))
                .contains("Stored 1 chunk(s)");

        Tool search = tool(new VectorTools.SearchFactory(), session(mine));
        Tool upsert = tool(new VectorTools.UpsertFactory(), session(mine));
        Tool delete = tool(new VectorTools.DeleteFileFactory(), session(mine));

        assertThat(search.execute(Map.of("store", theirStore, "query", "finance")))
                .startsWith("Error:").contains("another chat session").doesNotContain("report");
        assertThat(search.execute(Map.of("store", "their-notes", "query", "finance")))
                .startsWith("Error:").contains("another chat session");
        assertThat(upsert.execute(Map.of("store", theirStore, "file_id", "x",
                "chunks", List.of(Map.of("text", "planted")))))
                .startsWith("Error:").contains("another chat session");
        assertThat(delete.execute(Map.of("store", theirStore, "file_id", "secret.pdf")))
                .startsWith("Error:").contains("another chat session");
        assertThat(upsert.execute(Map.of("store", "session-" + SessionId.random().value(), "file_id", "x",
                "chunks", List.of(Map.of("text", "squatting")))))
                .startsWith("Error:");

        // The own store stays reachable, by omission and by name.
        assertThat(upsert.execute(Map.of("store", "session-" + mine.value(), "scope", "session",
                "file_id", "mine.pdf", "chunks", List.of(Map.of("text", "podman container notes")))))
                .contains("Stored 1 chunk(s)");
        assertThat(search.execute(Map.of("query", "container"))).contains("podman");
        assertThat(search.execute(Map.of("store", "session-" + mine.value(), "query", "container")))
                .contains("podman");

        // Knowledge bases are not session stores and stay open to everyone.
        assertThat(upsert.execute(Map.of("store", "kb", "file_id", "d",
                "chunks", List.of(Map.of("text", "shared knowledge")))))
                .contains("Stored 1 chunk(s)");
        assertThat(tool(new VectorTools.SearchFactory(), session(theirs))
                .execute(Map.of("store", "kb", "query", "knowledge"))).contains("shared");
    }
}
