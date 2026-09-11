package ai.mindconnect.agent.tools.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * What the file tools agree on when they walk or read a tree: the
 * directories nobody wants to search (build output, dependencies, VCS
 * internals) and what counts as a binary file.
 */
final class FileWalks {

    private FileWalks() {}

    /** Directories skipped while walking, whatever the pattern — build artefacts would drown the model. */
    static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", ".svn", ".hg",
            "target", "build", "dist", "out", "bin",
            "node_modules", ".gradle", ".mvn",
            ".idea", ".vscode", ".settings",
            "__pycache__", ".venv", "venv", ".tox",
            ".next", ".nuxt", ".cache"
    );

    /** How much of a file's head decides whether it is binary. */
    private static final int SNIFF_BYTES = 8_192;

    /**
     * Is the file binary — a NUL byte in its first 8 KB, or bytes that are
     * no UTF-8? The test {@code grep} and {@code file} use; a PDF or a Word
     * document counts as binary, and the document tools are for those.
     */
    static boolean isBinary(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(SNIFF_BYTES);
            return isBinary(head);
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isBinary(byte[] head) {
        for (byte b : head) {
            if (b == 0) return true;
        }
        // Strict UTF-8 decoding of the head: malformed input means it is no text.
        var decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            // The head may end mid-character; ignore an incomplete tail.
            var buffer = java.nio.ByteBuffer.wrap(head);
            var out = java.nio.CharBuffer.allocate(head.length);
            var result = decoder.decode(buffer, out, false);
            return result.isMalformed();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
