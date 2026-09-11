package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.runtime.tools.todo.TodoList;
import ai.mindconnect.agent.runtime.tools.todo.TodoListRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Persists the todo list of each session as:
 *   {base}/sessions/{sessionId}/todos.json
 *
 * <p>Aligned with how other session-scoped state (working memory) is laid
 * out today: one file per session, atomic full-replace on save.
 */
public class FileTodoListRepository implements TodoListRepository {

    private static final Logger log = LoggerFactory.getLogger(FileTodoListRepository.class);
    private static final String FILE_NAME = "todos.json";

    private final Path baseDir;
    private final ObjectMapper mapper;

    public FileTodoListRepository(Path baseDir, Namespace namespace) {
        this.baseDir = baseDir.resolve(namespace.value()).toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Optional<TodoList> findBySession(SessionId sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            // A list written before the namespace was recorded takes it from the session asked for.
            return Optional.of(mapper.readerFor(TodoList.class)
                    .readValue(file.toFile()));
        } catch (IOException e) {
            log.warn("Failed to read todo list for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public TodoList save(TodoList list) {
        Path file = fileFor(list.sessionId());
        try {
            Files.createDirectories(file.getParent());
            AtomicFiles.write(file, out -> mapper.writerWithDefaultPrettyPrinter().writeValue(out, list));
            log.debug("Saved todo list for session {} ({} items)", list.sessionId(), list.items().size());
            return list;
        } catch (IOException e) {
            log.warn("Failed to save todo list for session {}: {}", list.sessionId(), e.getMessage());
            throw new RuntimeException("Failed to persist todo list", e);
        }
    }

    @Override
    public void deleteBySession(SessionId sessionId) {
        Path file = fileFor(sessionId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete todo list for session {}: {}", sessionId, e.getMessage());
        }
    }

    private Path fileFor(SessionId sessionId) {
        return baseDir
                .resolve("sessions")
                .resolve(sessionId.value())
                .resolve(FILE_NAME);
    }
}
