package ai.mindconnect.filerepo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a file so that nobody ever reads it half-written.
 *
 * <p>The content goes into a temporary file beside the target, and that file is
 * moved over the target in one step. A reader sees the old content or the new,
 * never an empty or truncated file. Every replacement is a new file, which is
 * also why a reader needs no lock: the file it opened stays whole even while
 * the next version is moved into place.
 *
 * <p>This covers torn reads, not lost updates — serializing writers is
 * {@link PathLocks}' job. The temporary file starts with a dot and ends in
 * {@code .tmp}; {@link #isTemporary} recognises it, and {@link FileRepo}
 * deletes the ones a crash left behind.
 */
public final class FileWrites {

    /** Writes the content into the stream it is handed; the stream is closed afterwards either way. */
    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    private static final String TEMP_SUFFIX = ".tmp";

    private FileWrites() {
    }

    /** Replaces {@code target} with what {@code content} writes, creating the parent directories. */
    public static void write(Path target, Content content) throws IOException {
        Path file = target.toAbsolutePath();
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName() + ".", TEMP_SUFFIX);
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                content.writeTo(out);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // A file system without atomic rename: still better than truncating in place.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Replaces {@code target} with {@code text}, UTF-8. */
    public static void writeString(Path target, String text) throws IOException {
        write(target, out -> out.write(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether {@code file} is the temporary file of a write — in progress, or left behind by a crash. */
    public static boolean isTemporary(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(".") && name.endsWith(TEMP_SUFFIX);
    }
}
