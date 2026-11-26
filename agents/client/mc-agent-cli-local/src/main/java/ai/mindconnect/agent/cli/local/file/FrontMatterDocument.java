package ai.mindconnect.agent.cli.local.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Markdown file with a header between two {@code ---} lines: what agents
 * and skills are written as. The header is flat {@code key: value} lines —
 * no nesting, a list is comma-separated — because that is all an agent or a
 * skill needs to say about itself; the body is the text below.
 */
record FrontMatterDocument(Map<String, String> header, String body) {

    private static final String FENCE = "---";

    /** The document in this file; empty when there is no such file. */
    static Optional<FrontMatterDocument> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    static FrontMatterDocument parse(String text) {
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals(FENCE)) {
            return new FrontMatterDocument(Map.of(), text.strip());
        }
        Map<String, String> header = new LinkedHashMap<>();
        int line = 1;
        for (; line < lines.size() && !lines.get(line).strip().equals(FENCE); line++) {
            int colon = lines.get(line).indexOf(':');
            if (colon > 0) {
                header.put(lines.get(line).substring(0, colon).strip(), lines.get(line).substring(colon + 1).strip());
            }
        }
        String body = String.join("\n", lines.subList(Math.min(line + 1, lines.size()), lines.size()));
        return new FrontMatterDocument(header, body.strip());
    }

    String value(String key, String fallback) {
        return header.getOrDefault(key, fallback);
    }

    /** A comma-separated header value as a list; empty when the key is missing. */
    List<String> list(String key) {
        return Arrays.stream(header.getOrDefault(key, "").split(","))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .toList();
    }
}
