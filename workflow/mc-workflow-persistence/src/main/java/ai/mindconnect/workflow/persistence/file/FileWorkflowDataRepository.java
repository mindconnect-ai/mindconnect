package ai.mindconnect.workflow.persistence.file;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A {@link WorkflowDataRepository} backed by one {@code <id>.json} file per
 * workflow in a base directory.
 *
 * <p>Ids are sanitised to a safe file-name charset, so an id can never point
 * outside the base directory, and writes go through a temp file and an atomic
 * rename — a crash mid-save cannot leave a half-written workflow behind. The
 * app-specific stores this consolidates each got one of those wrong.
 */
public class FileWorkflowDataRepository implements WorkflowDataRepository {

    private static final String EXT = ".json";

    private final Path dataDir;
    private final Supplier<String> partition;
    private final JacksonWorkflowSerializer serializer;

    /**
     * @param baseDir   the data directory; the definitions live in
     *                  {@code <baseDir>/<partition>/workflows}
     * @param partition the directory level a call reads and writes, asked on
     *                  every call — one partition never sees the definitions of another
     */
    public FileWorkflowDataRepository(Path baseDir, Supplier<String> partition) {
        this(baseDir, partition, new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create()));
    }

    public FileWorkflowDataRepository(Path baseDir, Supplier<String> partition, JacksonWorkflowSerializer serializer) {
        this.dataDir = baseDir;
        this.partition = partition;
        this.serializer = serializer;
    }

    /**
     * The directory a repository over {@code baseDir} and {@code partition}
     * keeps its {@code <id>.json} files in — for a reader that must find them
     * without creating it, such as the import into another store.
     */
    public static Path directory(Path baseDir, String partition) {
        return baseDir.resolve(FileWorkflowInstanceRepository.checkPartition(partition)).resolve("workflows");
    }

    @Override
    public List<String> listIds() {
        try (Stream<Path> files = Files.list(workflowsDir())) {
            List<String> ids = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(EXT))
                    .map(p -> stripExt(p.getFileName().toString()))
                    .sorted()
                    .forEach(ids::add);
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list workflows in " + workflowsDir(), e);
        }
    }

    @Override
    public Optional<WorkflowData> findById(String id) {
        Path file = fileFor(id);
        return Files.exists(file) ? Optional.of(serializer.read(file)) : Optional.empty();
    }

    @Override
    public void save(String id, WorkflowData workflow) {
        Path target = fileFor(id);
        try {
            Path temp = Files.createTempFile(workflowsDir(), "wf", ".tmp");
            serializer.write(workflow, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write workflow " + id, e);
        }
    }

    @Override
    public boolean delete(String id) {
        try {
            return Files.deleteIfExists(fileFor(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete workflow " + id, e);
        }
    }

    private Path fileFor(String id) {
        return workflowsDir().resolve(sanitize(id) + EXT);
    }

    private static String stripExt(String name) {
        return name.substring(0, name.length() - EXT.length());
    }

    /** Keep ids to a safe file-name charset so they can't escape the base dir. */
    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** The definitions directory of the partition the current call works in, created on first use. */
    private Path workflowsDir() {
        Path dir = directory(dataDir, partition.get());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create workflow store dir: " + dir, e);
        }
        return dir;
    }
}
