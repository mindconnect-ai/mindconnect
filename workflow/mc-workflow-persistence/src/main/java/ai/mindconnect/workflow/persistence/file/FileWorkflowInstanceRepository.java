package ai.mindconnect.workflow.persistence.file;

import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Suspended instances as one JSON file each, under {@code <baseDir>/instances/}.
 *
 * <p>Writes go through a temporary file and an atomic rename: a suspended
 * instance is the only record that a run ever happened, and a half-written file
 * would lose it for good.
 */
public class FileWorkflowInstanceRepository implements WorkflowInstanceRepository {

    private final Path directory;
    private final SnapshotSerializer serializer;

    public FileWorkflowInstanceRepository(Path baseDir) {
        this(baseDir, new SnapshotSerializer());
    }

    public FileWorkflowInstanceRepository(Path baseDir, SnapshotSerializer serializer) {
        this.directory = baseDir.resolve("instances");
        this.serializer = serializer;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + directory, e);
        }
    }

    @Override
    public String save(WorkflowInstanceSnapshot snapshot) {
        if (snapshot.getInstanceId() == null || snapshot.getInstanceId().isBlank()) {
            snapshot.setInstanceId(UUID.randomUUID().toString());
        }
        String json = serializer.toJson(snapshot);
        Path target = fileFor(snapshot.getInstanceId());
        try {
            Path temp = Files.createTempFile(directory, "snapshot", ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
        return snapshot.getInstanceId();
    }

    @Override
    public Optional<WorkflowInstanceSnapshot> findById(String instanceId) {
        Path file = fileFor(instanceId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    @Override
    public List<WorkflowInstanceSnapshot> findByWorkflow(String workflowName) {
        return findAll().stream()
                .filter(s -> workflowName.equals(s.getWorkflowName()))
                .toList();
    }

    @Override
    public List<WorkflowInstanceSnapshot> findAll() {
        try (Stream<Path> files = Files.list(directory)) {
            List<WorkflowInstanceSnapshot> all = new ArrayList<>(files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .toList());
            all.sort(Comparator.comparingLong(WorkflowInstanceSnapshot::getSuspendedAt).reversed());
            return all;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory, e);
        }
    }

    @Override
    public boolean delete(String instanceId) {
        try {
            return Files.deleteIfExists(fileFor(instanceId));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete instance " + instanceId, e);
        }
    }

    private WorkflowInstanceSnapshot read(Path file) {
        try {
            return serializer.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Ids are generated here, but a caller could hand one in — keep it a file name. */
    private Path fileFor(String instanceId) {
        String safe = instanceId.replaceAll("[^A-Za-z0-9._-]", "_");
        return directory.resolve(safe + ".json");
    }
}
