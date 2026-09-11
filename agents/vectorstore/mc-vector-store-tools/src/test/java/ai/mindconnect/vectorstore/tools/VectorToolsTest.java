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

import java.nio.file.Files;
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
                    case "dataBaseDir", "defaultBaseDir" -> Optional.of(dir.toString());
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

    private static ToolCallScope chat(String user, SessionId sessionId) {
        return new ToolCallScope(UserId.of(user), sessionId, null);
    }

    private VectorStores stores() {
        return VectorStores.fromEnvironment(env).orElseThrow();
    }

    /** A chat's upload store as the upload pipeline registers it: session-scoped, owned by the chat's user. */
    private String uploadStore(String owner, SessionId chat) {
        String name = "session-" + chat.value();
        stores().open(name, null, VectorStoreInstance.Scope.SESSION, chat.value(), owner);
        return name;
    }

    private static Map<String, Object> chunk(String store, String fileId, String text) {
        return Map.of("store", store, "file_id", fileId, "chunks", List.of(Map.of("text", text)));
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
     * A chat's upload store belongs to the chat's user. Calls made for that
     * user reach it — the ingestion workflow run on their behalf, another chat
     * of theirs, a sub-agent their chat started. Calls for anyone else are
     * refused, and so are calls for nobody in particular: a workflow started
     * from the workflow admin or the REST API.
     */
    @Test
    void aChatsUploadStoreIsReachableOnlyForItsUser() {
        SessionId bobsChat = SessionId.random();
        String bobsStore = uploadStore("bob", bobsChat);

        // The ingestion workflow runs on behalf of bob's chat and fills the store.
        assertThat(tool(new VectorTools.UpsertFactory(), chat("bob", bobsChat))
                .execute(chunk(bobsStore, "secret.pdf", "the finance report")))
                .contains("Stored 1 chunk(s)");
        // So does a session-scoped store under a free name.
        assertThat(tool(new VectorTools.UpsertFactory(), chat("bob", bobsChat)).execute(Map.of(
                "store", "bob-notes", "scope", "session", "file_id", "notes",
                "chunks", List.of(Map.of("text", "finance notes")))))
                .contains("Stored 1 chunk(s)");

        // Runs for nobody in particular reach no chat's store.
        assertThat(tool(new VectorTools.SearchFactory())
                .execute(Map.of("store", bobsStore, "query", "finance")))
                .startsWith("Error:").contains("another chat's uploads");
        assertThat(tool(new VectorTools.UpsertFactory(), ToolCallScope.detached(UserId.of("workflow")))
                .execute(chunk(bobsStore, "x", "planted")))
                .startsWith("Error:").contains("another chat's uploads");
        // Not even when it carries the owner's user id: only a call in a chat acts for a user.
        assertThat(tool(new VectorTools.SearchFactory(), ToolCallScope.detached(UserId.of("bob")))
                .execute(Map.of("store", bobsStore, "query", "finance")))
                .startsWith("Error:").contains("another chat's uploads");

        // Alice, from her own chat: search, upsert and delete are refused ...
        ToolCallScope alice = chat("alice", SessionId.random());
        assertThat(tool(new VectorTools.SearchFactory(), alice)
                .execute(Map.of("store", bobsStore, "query", "finance")))
                .startsWith("Error:").doesNotContain("report");
        assertThat(tool(new VectorTools.SearchFactory(), alice)
                .execute(Map.of("store", "bob-notes", "query", "finance")))
                .startsWith("Error:");
        assertThat(tool(new VectorTools.UpsertFactory(), alice).execute(chunk(bobsStore, "x", "planted")))
                .startsWith("Error:");
        assertThat(tool(new VectorTools.DeleteFileFactory(), alice)
                .execute(Map.of("store", bobsStore, "file_id", "secret.pdf")))
                .startsWith("Error:");
        // ... and so is a session name nobody registered: no squatting.
        assertThat(tool(new VectorTools.UpsertFactory(), alice)
                .execute(chunk("session-" + SessionId.random().value(), "x", "squatting")))
                .startsWith("Error:");

        // Bob reaches his store from another chat of his ...
        assertThat(tool(new VectorTools.SearchFactory(), chat("bob", SessionId.random()))
                .execute(Map.of("store", bobsStore, "query", "finance")))
                .contains("the finance report");
        // ... and a sub-agent his chat started searches it without naming it.
        ToolCallScope subAgent = new ToolCallScope(UserId.of("bob"), SessionId.random(), null, bobsChat);
        assertThat(tool(new VectorTools.SearchFactory(), subAgent).execute(Map.of("query", "finance")))
                .contains("the finance report");

        // Knowledge bases are not chat stores and stay open to everyone.
        assertThat(tool(new VectorTools.UpsertFactory(), alice).execute(chunk("kb", "d", "shared knowledge")))
                .contains("Stored 1 chunk(s)");
        assertThat(tool(new VectorTools.SearchFactory(), chat("bob", bobsChat))
                .execute(Map.of("store", "kb", "query", "knowledge")))
                .contains("shared");
    }

    /**
     * An upload store registered before owners were recorded is reachable
     * from its own chat only — until its chat writes to it again, through the
     * upload pipeline or a tool, and the chat's user is recorded as its owner.
     */
    @Test
    void anUploadStoreWithoutOwnerIsReachableFromItsOwnChatUntilItGetsOne() {
        SessionId oldChat = SessionId.random();
        String oldStore = "session-" + oldChat.value();
        stores().open(oldStore, null, VectorStoreInstance.Scope.SESSION, oldChat.value());

        assertThat(tool(new VectorTools.SearchFactory(), chat("carol", oldChat))
                .execute(Map.of("query", "container")))
                .contains("No results");
        assertThat(tool(new VectorTools.SearchFactory(), chat("carol", SessionId.random()))
                .execute(Map.of("store", oldStore, "query", "container")))
                .startsWith("Error:");

        // Its own chat writes to it: the store is claimed for the chat's user.
        assertThat(tool(new VectorTools.UpsertFactory(), chat("carol", oldChat))
                .execute(chunk(oldStore, "notes", "container notes")))
                .contains("Stored 1 chunk(s)");
        assertThat(stores().registry().instance(oldStore).orElseThrow().owner()).isEqualTo("carol");

        assertThat(tool(new VectorTools.SearchFactory(), chat("carol", SessionId.random()))
                .execute(Map.of("store", oldStore, "query", "container")))
                .contains("container notes");
    }

    /**
     * A chat's own store is the chat's however it came about: a tool writing
     * into it before any upload registers it so, and the upload pipeline claims
     * a store registered under the chat's name with another scope — but never
     * one that already has an owner or belongs to another chat.
     */
    @Test
    void aChatsOwnStoreIsRegisteredAsTheChatsWhoeverCreatesIt() {
        SessionId davesChat = SessionId.random();
        String davesStore = "session-" + davesChat.value();
        assertThat(tool(new VectorTools.UpsertFactory(), chat("dave", davesChat))
                .execute(chunk(davesStore, "early", "container notes")))
                .contains("Stored 1 chunk(s)");
        VectorStoreInstance registered = stores().registry().instance(davesStore).orElseThrow();
        assertThat(registered.scope()).isEqualTo(VectorStoreInstance.Scope.SESSION);
        assertThat(registered.scopeRef()).isEqualTo(davesChat.value());
        assertThat(registered.owner()).isEqualTo("dave");
        assertThat(tool(new VectorTools.SearchFactory(), chat("dave", SessionId.random()))
                .execute(Map.of("store", davesStore, "query", "container")))
                .contains("container notes");

        // Registered under the chat's name as GLOBAL: the chat's pipeline claims it.
        SessionId erinsChat = SessionId.random();
        String erinsStore = "session-" + erinsChat.value();
        stores().open(erinsStore, null, VectorStoreInstance.Scope.GLOBAL, null);
        stores().open(erinsStore, null, VectorStoreInstance.Scope.SESSION, erinsChat.value(), "erin");
        VectorStoreInstance claimed = stores().registry().instance(erinsStore).orElseThrow();
        assertThat(claimed.scope()).isEqualTo(VectorStoreInstance.Scope.SESSION);
        assertThat(claimed.scopeRef()).isEqualTo(erinsChat.value());
        assertThat(claimed.owner()).isEqualTo("erin");
        // An owned store is not claimed again, and nobody claims another chat's name.
        stores().open(erinsStore, null, VectorStoreInstance.Scope.SESSION, erinsChat.value(), "mallory");
        assertThat(stores().registry().instance(erinsStore).orElseThrow().owner()).isEqualTo("erin");
        String franksStore = "session-" + SessionId.random().value();
        stores().open(franksStore, null, VectorStoreInstance.Scope.GLOBAL, null);
        stores().open(franksStore, null, VectorStoreInstance.Scope.SESSION, SessionId.random().value(), "mallory");
        assertThat(stores().registry().instance(franksStore).orElseThrow().owner()).isNull();
    }

    @Test
    void ingestFileFollowsTheSameRule() throws Exception {
        Files.writeString(dir.resolve("doc.txt"), "finance numbers");
        String bobsStore = uploadStore("bob", SessionId.random());
        Tool ingest = tool(new VectorIngestFileTool.Factory(), chat("alice", SessionId.random()));

        assertThat(ingest.execute(Map.of("path", "doc.txt", "store", bobsStore)))
                .startsWith("Error:").contains("another chat's uploads");
        assertThat(ingest.execute(Map.of("path", "doc.txt", "store", "kb")))
                .contains("Stored");

        // Its own chat's store, written before any upload, is registered as that chat's.
        SessionId alicesChat = SessionId.random();
        String alicesStore = "session-" + alicesChat.value();
        assertThat(tool(new VectorIngestFileTool.Factory(), chat("alice", alicesChat))
                .execute(Map.of("path", "doc.txt", "store", alicesStore)))
                .contains("Stored");
        assertThat(stores().registry().instance(alicesStore).orElseThrow().owner()).isEqualTo("alice");
    }
}
