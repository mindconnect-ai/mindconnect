package ai.mindconnect.common.util;

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
 * never an empty or truncated file — which is what a plain
 * {@code Files.writeString(target, …)} or {@code mapper.writeValue(file, …)}
 * hands out while it truncates and rewrites. File-backed stores are read while
 * other threads write them (a tool task loading the history its sibling is
 * appending to), so every store write goes through here.
 *
 * <p>This covers torn reads, not lost updates: two writers still overwrite each
 * other, the last one wins. The temporary file starts with a dot and ends in
 * {@code .tmp}, so directory listings that filter by extension skip it.
 */
public final class AtomicFiles {

    /** Writes the content into the stream it is handed; the stream is closed afterwards either way. */
    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    private AtomicFiles() {
    }

    /** Replaces {@code target} with what {@code content} writes, creating the parent directories. */
    public static void write(Path target, Content content) throws IOException {
        Path file = target.toAbsolutePath();
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName() + ".", ".tmp");
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
}
