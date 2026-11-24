package ai.mindconnect.agentrest.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAttachment;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.directory.UserHome;
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

    private ai.mindconnect.agentrest.SessionFixture sessions;
    private UserHome home;
    private StubFileStore files;
    private SessionFileService service;

    @BeforeEach
    void setUp() {
        sessions = new ai.mindconnect.agentrest.SessionFixture();
        home = UserHome.of(temp.resolve("home").resolve("{user}").toString());
        files = new StubFileStore();
        VectorStores stores = VectorStores.fromEnvironment(new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) mock(LlmEmbeddings.class));
                if (type == LlmConfigRepository.class) return Optional.of((T) mock(LlmConfigRepository.class));
                if (type == ScopeSupplier.class) return Optional.of((T) ScopeSupplier.fixed(new Namespace("test")));
                return Optional.empty();
            }

            @Override
            public Optional<String> getString(String key) {
                return "dataBaseDir".equals(key) ? Optional.of(temp.resolve("data").toString()) : Optional.empty();
            }
        }).orElseThrow();
        // Direct ingestion — no workflow engine; an empty file needs no embeddings either.
        stores.registry().saveTemplate(new VectorStoreTemplate(SessionFileService.CHAT_UPLOADS_TEMPLATE,
                "embeddings", null, Map.of()));
        service = new SessionFileService(files, provider(stores), provider(null), sessions.runtime(),
                provider(null), temp.resolve("tools").toString(), provider(home),
                provider(null));
    }

    @Test
    void aChatWorkingInAProjectReachesItsUploads() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        ManagedSession session = sessions.open("alice", project.toString());

        var result = service.attach(id(session), files.save("report.md", "text/markdown",
                new ByteArrayInputStream(new byte[0])));

        assertThat(result.success()).as(result.message()).isTrue();
        Path own = home.sessionDirOf("alice", session.id().value()).orElseThrow();
        ManagedSession after = sessions.management().find(session.id()).orElseThrow();
        assertThat(pathOf(after, "report.md")).isEqualTo(own.resolve("uploads").resolve("report.md").toString());
        assertThat(after.directories().working()).isEqualTo(project.toString());
        assertThat(after.directories().additional()).as("the uploads' directory is reachable")
                .containsExactly(own.toString());

        service.attach(id(session), files.save("second.md", "text/markdown",
                new ByteArrayInputStream(new byte[0])));
        assertThat(sessions.management().find(session.id()).orElseThrow().directories().additional())
                .as("added once").containsExactly(own.toString());
    }

    @Test
    void aChatInItsOwnDirectory_orAboveIt_getsNothingAdded() throws Exception {
        ManagedSession inOwn = sessions.open("bob", null);
        inOwn = sessions.management().changeDirectories(inOwn.id(), new SessionDirectories(
                home.sessionDirOf("bob", inOwn.id().value()).orElseThrow().toString(), List.of()));
        ManagedSession inHome = sessions.open("bob", home.homeOf("bob").orElseThrow().toString());

        for (ManagedSession session : List.of(inOwn, inHome)) {
            var result = service.attach(id(session), files.save("notes.md", "text/markdown",
                    new ByteArrayInputStream(new byte[0])));

            assertThat(result.success()).as(result.message()).isTrue();
            ManagedSession after = sessions.management().find(session.id()).orElseThrow();
            assertThat(pathOf(after, "notes.md")).isNotNull();
            assertThat(after.directories().additional()).isEmpty();
        }
    }

    @Test
    void anImageIsCopiedIntoTheUploadsDirectoryToo() throws Exception {
        ManagedSession session = sessions.open("carol", null);
        byte[] pixels = {(byte) 0x89, 'P', 'N', 'G'};

        var result = service.attach(id(session), files.save("shot.png", "image/png",
                new ByteArrayInputStream(pixels)));

        assertThat(result.success()).as(result.message()).isTrue();
        Path copy = home.sessionDirOf("carol", session.id().value()).orElseThrow().resolve("uploads").resolve("shot.png");
        assertThat(copy).as("the file tools list it like any other upload").exists()
                .hasBinaryContent(pixels);
        assertThat(pathOf(sessions.management().find(session.id()).orElseThrow(), "shot.png"))
                .isEqualTo(copy.toString());

        service.deleteAttachment(id(session), "shot.png");
        assertThat(copy).as("detaching takes the copy with it").doesNotExist();
        assertThat(sessions.management().find(session.id()).orElseThrow().attachments()).isEmpty();
    }

    private static ai.mindconnect.agent.SessionId id(ManagedSession session) {
        return session.id();
    }

    private static String pathOf(ManagedSession session, String fileName) {
        return session.attachments().stream().filter(file -> file.name().equals(fileName))
                .map(SessionAttachment::path).findFirst().orElseThrow();
    }

    // ── Stubs ──────────────────────────────────────────────────────────────

    /** Enough of a file store to hold bytes and hand them back. */
    private static final class StubFileStore implements FileStore {
        private final Map<FileId, byte[]> stored = new LinkedHashMap<>();
        private final Map<FileId, StoredFile> meta = new LinkedHashMap<>();

        @Override
        public StoredFile save(String name, String contentType, InputStream content,
                               UserId creator) throws IOException {
            return save(name, contentType, content);
        }

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
