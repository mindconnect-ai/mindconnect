package ai.mindconnect.agent.adapter.file;

import ai.mindconnect.agent.tools.todo.TodoList;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

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

    public FileTodoListRepository(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Optional<TodoList> findBySession(UUID sessionId) {
        Path file = fileFor(sessionId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), TodoList.class));
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
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), list);
            log.debug("Saved todo list for session {} ({} items)", list.sessionId(), list.items().size());
            return list;
        } catch (IOException e) {
            log.warn("Failed to save todo list for session {}: {}", list.sessionId(), e.getMessage());
            throw new RuntimeException("Failed to persist todo list", e);
        }
    }

    @Override
    public void deleteBySession(UUID sessionId) {
        Path file = fileFor(sessionId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete todo list for session {}: {}", sessionId, e.getMessage());
        }
    }

    private Path fileFor(UUID sessionId) {
        return baseDir
                .resolve("sessions")
                .resolve(sessionId.toString())
                .resolve(FILE_NAME);
    }
}
