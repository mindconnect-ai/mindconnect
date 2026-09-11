package ai.mindconnect.agentrest.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.service.UserHome;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The copy of an attached file lands in the session's own directory, and the
 * prompt names its path. A chat working in a project must still reach it with
 * the file tools — which go only where the session's directories are.
 */
class SessionFileServiceWorkingDirTest {

    @TempDir
    Path temp;

    private InMemoryAgentSessionRepository sessions;
    private UserHome home;
    private StubFileStore files;
    private SessionFileService service;

    @BeforeEach
    void setUp() {
        sessions = new InMemoryAgentSessionRepository();
        home = UserHome.of(temp.resolve("home").resolve("{user}").toString());
        files = new StubFileStore();
        VectorStores stores = VectorStores.fromEnvironment(new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) mock(LlmEmbeddings.class));
                if (type == LlmConfigRepository.class) return Optional.of((T) mock(LlmConfigRepository.class));
                if (type == Namespace.class) return Optional.of((T) new Namespace("test"));
                return Optional.empty();
            }

            @Override
            public Optional<String> getString(String key) {
                return "dataBaseDir".equals(key) ? Optional.of(temp.resolve("data").toString()) : Optional.empty();
            }
        }).orElseThrow();
        // Direct ingestion — no workflow engine; an empty file needs no embeddings either.
        stores.registry().saveTemplate(new VectorStoreTemplate(SessionFileService.CHAT_UPLOADS_TEMPLATE,
                "memory", Map.of(), "embeddings", null, Map.of()));
        service = new SessionFileService(files, provider(stores), provider(null), sessions,
                provider(null), provider(null), temp.resolve("tools").toString(), provider(home));
    }

    @Test
    void aChatWorkingInAProjectReachesItsUploads() throws Exception {
        UserId alice = UserId.of("alice");
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        AgentSession session = sessions.save(AgentSession.start(AgentId.random(), alice, ConversationId.random())
                .withWorkingDir(project.toString()));

        var result = service.attach(session.id(), files.save("report.md", "text/markdown",
                new ByteArrayInputStream(new byte[0])));

        assertThat(result.success()).as(result.message()).isTrue();
        Path own = home.sessionDirOf(alice, session.id()).orElseThrow();
        AgentSession after = sessions.findById(session.id()).orElseThrow();
        assertThat(after.attachedFile("report.md")).get().extracting(AttachedFile::path)
                .isEqualTo(own.resolve("uploads").resolve("report.md").toString());
        assertThat(after.workingDir()).isEqualTo(project.toString());
        assertThat(after.additionalDirs()).as("the uploads' directory is reachable")
                .containsExactly(own.toString());

        service.attach(session.id(), files.save("second.md", "text/markdown",
                new ByteArrayInputStream(new byte[0])));
        assertThat(sessions.findById(session.id()).orElseThrow().additionalDirs())
                .as("added once").containsExactly(own.toString());
    }

    @Test
    void aChatInItsOwnDirectory_orAboveIt_getsNothingAdded() throws Exception {
        UserId bob = UserId.of("bob");
        AgentSession inOwn = AgentSession.start(AgentId.random(), bob, ConversationId.random());
        inOwn = sessions.save(inOwn.withWorkingDir(home.sessionDirOf(bob, inOwn.id()).orElseThrow().toString()));
        AgentSession inHome = sessions.save(AgentSession.start(AgentId.random(), bob, ConversationId.random())
                .withWorkingDir(home.homeOf(bob).orElseThrow().toString()));

        for (AgentSession session : List.of(inOwn, inHome)) {
            var result = service.attach(session.id(), files.save("notes.md", "text/markdown",
                    new ByteArrayInputStream(new byte[0])));

            assertThat(result.success()).as(result.message()).isTrue();
            AgentSession after = sessions.findById(session.id()).orElseThrow();
            assertThat(after.attachedFile("notes.md")).get().extracting(AttachedFile::path).isNotNull();
            assertThat(after.additionalDirs()).isEmpty();
        }
    }

    // ── Stubs ──────────────────────────────────────────────────────────────

    /** Enough of a file store to hold bytes and hand them back. */
    private static final class StubFileStore implements FileStore {
        private final Map<FileId, byte[]> stored = new LinkedHashMap<>();
        private final Map<FileId, StoredFile> meta = new LinkedHashMap<>();

        @Override
        public StoredFile save(String name, String contentType, InputStream content) throws IOException {
            FileId id = FileId.of("file-" + UUID.randomUUID());
            byte[] bytes = content.readAllBytes();
            stored.put(id, bytes);
            StoredFile file = new StoredFile(id, name, contentType, bytes.length, Instant.now());
            meta.put(id, file);
            return file;
        }

        @Override public Optional<StoredFile> find(FileId id) { return Optional.ofNullable(meta.get(id)); }

        @Override public InputStream content(FileId id) { return new ByteArrayInputStream(stored.get(id)); }

        @Override public List<StoredFile> list() { return meta.values().stream().toList(); }

        @Override public void delete(FileId id) {
            stored.remove(id);
            meta.remove(id);
        }
    }

    /** The single-value ObjectProvider the service asks for its optional collaborators. */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}
