package ai.mindconnect.filerepo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One partition of a data directory — {@code <base>/<partition>}, where the
 * partition is typically a namespace. There is exactly one instance per
 * partition directory in a JVM: {@link #open} resolves the real path — a
 * relative spelling, a {@code ..} or a symlink all lead to the same instance.
 *
 * <p>Opening a partition
 * <ul>
 *   <li>locks {@value #LOCK_FILE} in the partition directory with an
 *       operating-system file lock, so a second process on the same partition
 *       fails at once instead of quietly overwriting this one's files. The base
 *       directory itself is never locked: processes serving different
 *       partitions share it freely;</li>
 *   <li>deletes, in the background, the temporary files of writes that a
 *       crash interrupted ({@link FileWrites#isTemporary}), leaving any younger
 *       than a minute alone because they may belong to a write in progress.</li>
 * </ul>
 *
 * <p>The operating system releases the lock when the process ends, a crash
 * included. Do not open {@value #LOCK_FILE} anywhere else: on POSIX systems
 * closing any channel to the file releases the process's lock on it.
 */
public class FileRepo {

    private static final Logger log = LoggerFactory.getLogger(FileRepo.class);

    static final String LOCK_FILE = ".mc-partition.lock";

    private static final Duration STALE_TEMPORARY = Duration.ofMinutes(1);

    private static final ConcurrentHashMap<Path, FileRepo> OPEN = new ConcurrentHashMap<>();

    /** How many {@link RecordLog} directories keep their index in memory; the least recently used go first. */
    private static final int MAX_LOG_STATES = 1024;

    private final Path root;

    @SuppressWarnings({"unused", "FieldCanBeLocal"}) // held for the lifetime of the JVM
    private final FileLock processLock;

    /**
     * The indexes of the {@link RecordLog} directories, shared by every log on this
     * partition. A dropped index is read again from the file names on next use.
     */
    private final Map<Path, LogState> logStates = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Path, LogState> eldest) {
                    return size() > MAX_LOG_STATES;
                }
            });

    private FileRepo(Path root) {
        this.root = root;
        this.processLock = lockAgainstOtherProcesses(root);
        Thread.ofVirtual().name("mc-file-repo-cleanup").start(() -> deleteTemporaryFilesOlderThan(STALE_TEMPORARY));
    }

    /**
     * The partition {@code partition} of the data directory {@code base}, created
     * if missing.
     *
     * @param partition one directory name — a namespace, typically; no separators, not {@code .} or {@code ..}
     * @throws FileRepoException when another process has the partition open
     */
    public static FileRepo open(Path base, String partition) {
        requireDirectoryName(partition);
        Path real;
        try {
            Path dir = base.resolve(partition);
            Files.createDirectories(dir);
            real = dir.toRealPath();
        } catch (IOException e) {
            throw new FileRepoException("Cannot open partition '" + partition + "' of data directory " + base, e);
        }
        return OPEN.computeIfAbsent(real, FileRepo::new);
    }

    /** The real path of the partition directory. */
    public Path root() {
        return root;
    }

    /**
     * {@code relative} resolved against the partition directory. A path that leads
     * out of it — {@code ../other-namespace/…}, an absolute path — is refused:
     * keys come from ids, and ids come from requests.
     */
    public Path resolve(String relative) {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Path leads out of the partition " + root + ": " + relative);
        }
        return file;
    }

    LogState logState(Path dir) {
        return logStates.get(dir);
    }

    void putLogState(Path dir, LogState state) {
        logStates.put(dir, state);
    }

    /** Drops every in-memory index, as a restart would. */
    void forgetLogStates() {
        logStates.clear();
    }

    /** Deletes the temporary files of interrupted writes that are older than {@code age}; returns how many. */
    int deleteTemporaryFilesOlderThan(Duration age) {
        Instant cutoff = Instant.now().minus(age);
        int[] deleted = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (FileWrites.isTemporary(file) && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        try {
                            if (Files.deleteIfExists(file)) deleted[0]++;
                        } catch (IOException e) {
                            log.warn("Could not delete leftover temporary file {}: {}", file, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;   // deleted while walking — nothing to clean there
                }
            });
        } catch (IOException e) {
            log.warn("Could not look for leftover temporary files in {}: {}", root, e.getMessage());
        }
        if (deleted[0] > 0) {
            log.info("Deleted {} temporary file(s) of interrupted writes in {}", deleted[0], root);
        }
        return deleted[0];
    }

    private static void requireDirectoryName(String partition) {
        if (partition == null || partition.isBlank() || partition.equals(".") || partition.equals("..")
                || partition.contains("/") || partition.contains("\\")) {
            throw new IllegalArgumentException("A partition is one directory name, got: " + partition);
        }
    }

    private static FileLock lockAgainstOtherProcesses(Path root) {
        Path lockFile = root.resolve(LOCK_FILE);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new FileRepoException("Partition " + root + " is in use by another process ("
                        + LOCK_FILE + " is locked). One process serves a partition; stop the other one "
                        + "or give this one a partition of its own.");
            }
            return lock;
        } catch (IOException | OverlappingFileLockException e) {
            closeQuietly(channel);
            throw new FileRepoException("Cannot lock partition " + root + " via " + lockFile, e);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }
}
