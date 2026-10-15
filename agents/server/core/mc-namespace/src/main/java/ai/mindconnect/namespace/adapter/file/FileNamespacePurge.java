package ai.mindconnect.namespace.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespacePurge;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.namespace.service.NamespaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Removes a namespace's directory: with file persistence everything of a
 * namespace lives under {@code <base>/<namespace>/} — agents, sessions,
 * conversations, workflows, MCP registrations, vector-store settings, user
 * homes — so deleting that one tree is the whole purge. The partition is
 * closed in this process first, releasing its lock.
 *
 * <p>Refuses anything that is not a plain namespace id, and the
 * installation's own {@code system} directory, whatever it is asked.
 */
public class FileNamespacePurge implements NamespacePurge {

    private static final Logger log = LoggerFactory.getLogger(FileNamespacePurge.class);

    private final Path baseDir;

    public FileNamespacePurge(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath().normalize();
    }

    @Override
    public void purge(Namespace namespace) {
        String id = namespace.value();
        if (!NamespaceService.ID.matcher(id).matches() || NamespaceService.RESERVED.contains(id)) {
            throw new IllegalArgumentException("Refusing to delete the directory of '" + id + "'");
        }
        Path dir = baseDir.resolve(id).normalize();
        if (!dir.startsWith(baseDir) || dir.equals(baseDir)) {
            throw new IllegalArgumentException("Refusing to delete '" + dir + "'");
        }
        // Close the partition first: its lock goes with the directory, and the namespace, created
        // again under this id, must open fresh and take a lock of its own.
        FileRepo.close(baseDir, id);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + dir, e);
        }
        log.info("Deleted the directory of namespace '{}': {}", id, dir);
    }
}
