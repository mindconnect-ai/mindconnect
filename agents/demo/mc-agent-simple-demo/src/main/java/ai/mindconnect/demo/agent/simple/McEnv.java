package ai.mindconnect.demo.agent.simple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the repository's {@code mc.env} file — plain docker-style
 * {@code KEY=VALUE} lines, no quoting or escaping (values are taken verbatim
 * after the first {@code =}), {@code #} starts a comment. The file is looked
 * up in the working directory and then upwards, so the demos find it no
 * matter whether they run from the repo root or the module directory.
 * Process environment variables win over file entries.
 */
final class McEnv {

    private static final Map<String, String> VALUES = load();

    private McEnv() {}

    /** The value for {@code key}: process env first, then mc.env, else {@code fallback}. */
    static String get(String key, String fallback) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return VALUES.getOrDefault(key, fallback);
    }

    private static Map<String, String> load() {
        Map<String, String> values = new LinkedHashMap<>();
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path file = dir.resolve("mc.env");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.strip();
                    int eq = trimmed.indexOf('=');
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || eq <= 0) {
                        continue;
                    }
                    values.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read " + file + ": " + e.getMessage());
            }
            break;   // first mc.env found wins
        }
        return values;
    }
}
