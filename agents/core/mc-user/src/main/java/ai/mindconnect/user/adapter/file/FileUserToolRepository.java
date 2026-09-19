package ai.mindconnect.user.adapter.file;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.UserTool;
import ai.mindconnect.user.domain.UserToolId;
import ai.mindconnect.user.port.out.UserToolRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserToolRepository} on the file system: one JSON document per binding
 * under {@code <storageDir>/system/user-tools/<id>.json} — installation-wide,
 * beside the users, their tokens and their connections.
 */
public class FileUserToolRepository implements UserToolRepository {

    private final FileDocuments<UserTool> tools;
    private final Object lock = new Object();

    public FileUserToolRepository(Path storageDir) {
        Objects.requireNonNull(storageDir, "storageDir");
        this.tools = new FileDocuments<>(storageDir.resolve("system").resolve("user-tools"), UserTool.class);
    }

    @Override
    public void save(UserTool tool) {
        synchronized (lock) {
            tools.write(tool.id().value(), tool);
        }
    }

    @Override
    public Optional<UserTool> findById(UserToolId id) {
        return tools.read(id.value()).filter(tool -> tool.id().equals(id));
    }

    @Override
    public List<UserTool> findByUser(UserId userId) {
        return tools.readAll().stream().filter(tool -> tool.userId().equals(userId)).toList();
    }

    @Override
    public void deleteById(UserToolId id) {
        synchronized (lock) {
            tools.delete(id.value());
        }
    }
}
