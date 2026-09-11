package ai.mindconnect.agent.tools.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * What the file tools agree on when they walk or read a tree: the
 * directories nobody wants to search (build output, dependencies, VCS
 * internals), what counts as a binary file, and how a text file is decoded.
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

    /** Above this share of control characters in the head a file without NUL bytes still counts as binary. */
    private static final double MAX_CONTROL_SHARE = 0.10;

    /**
     * Is the file binary — a NUL byte in its first 8 KB, a PDF header, or a
     * head that is mostly control characters? The test {@code grep} uses; a
     * PDF or a Word document counts as binary, and the document tools are for
     * those. Text in a legacy encoding — ISO-8859-1, Windows-1252 — is text:
     * the bytes that are no UTF-8 say nothing about binary.
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
        if (head.length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') {
            return true;
        }
        int control = 0;
        for (byte b : head) {
            if (b == 0) return true;
            // Tab, line feed, form feed, carriage return and escape (coloured logs) are text.
            if ((b > 0 && b < 0x20 && b != '\t' && b != '\n' && b != '\f' && b != '\r' && b != 0x1B) || b == 0x7F) {
                control++;
            }
        }
        return head.length > 0 && control > head.length * MAX_CONTROL_SHARE;
    }

    /**
     * The file's text: UTF-8 when its bytes are UTF-8, else ISO-8859-1 —
     * which decodes every byte, so a Latin-1 or Windows-1252 file reads with
     * its umlauts instead of failing.
     */
    static Decoded readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return new Decoded(text, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return new Decoded(new String(bytes, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
        }
    }

    /** A file's text and the charset it was read with — the one to write it back in. */
    record Decoded(String text, Charset charset) {}
}
