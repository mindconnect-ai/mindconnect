package ai.mindconnect.agent.tools.code;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The languages the code-execution tool offers: each is an image plus the
 * command that reads a program from stdin and runs it. Python and Node ship
 * as defaults; configuration can override images or add languages without a
 * code change.
 */
public final class CodeLanguages {

    /**
     * One runnable language. {@code command} must read the program from stdin
     * (the {@code -} convention both python3 and node support). {@code contents}
     * says what the image carries beyond the interpreter, for the model — null
     * when nobody knows, as for an image set in configuration.
     */
    public record CodeLanguage(String name, String image, List<String> command, String contents) {
        public CodeLanguage(String name, String image, List<String> command) {
            this(name, image, command, null);
        }
    }

    /** The python image Mindconnect publishes: the office libraries and generators the skills use. */
    public static final String PYTHON_IMAGE = "ghcr.io/mindconnect-ai/code-exec-python:latest";

    /** What {@link #PYTHON_IMAGE} carries, as the tool tells the model. */
    static final String PYTHON_IMAGE_CONTENTS = "the standard library plus python-pptx, python-docx, openpyxl, "
            + "matplotlib (Agg backend: save charts to files) and pandas, and the mc_office package whose "
            + "pptx_builder, docx_builder and xlsx_builder modules build a deck, a Word document or a workbook "
            + "from a spec with build(spec, path)";

    private CodeLanguages() {}

    public static Map<String, CodeLanguage> defaults() {
        Map<String, CodeLanguage> languages = new LinkedHashMap<>();
        languages.put("python", new CodeLanguage("python", PYTHON_IMAGE, List.of("python3", "-"),
                PYTHON_IMAGE_CONTENTS));
        languages.put("node", new CodeLanguage("node", "node:22-slim", List.of("node", "-")));
        return languages;
    }

    /**
     * Applies the {@code mindconnect.code-exec.languages} setting on top of
     * the defaults. Comma-separated entries, each either
     * {@code name=image} (override the image, keep the known command) or
     * {@code name=image|command args...} (add or fully redefine a language).
     * An unknown name without an explicit command falls back to
     * {@code name -}, matching the stdin convention of most interpreters.
     *
     * <p>Example: {@code python=python:3.13-slim,ruby=ruby:3.3-slim|ruby -}
     */
    public static Map<String, CodeLanguage> parse(String config) {
        Map<String, CodeLanguage> languages = defaults();
        if (config == null || config.isBlank()) {
            return languages;
        }
        for (String entry : config.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException(
                        "Invalid code-exec language entry (expected name=image[|command]): " + trimmed);
            }
            String name = trimmed.substring(0, eq).trim().toLowerCase();
            String rest = trimmed.substring(eq + 1).trim();
            int pipe = rest.indexOf('|');
            String image = pipe < 0 ? rest : rest.substring(0, pipe).trim();
            CodeLanguage known = languages.get(name);
            List<String> command;
            if (pipe >= 0) {
                command = Arrays.stream(rest.substring(pipe + 1).trim().split("\\s+")).toList();
            } else {
                command = known != null ? known.command() : List.of(name, "-");
            }
            // Another image: what the default carried is no longer known to be there.
            String contents = known != null && known.image().equals(image) ? known.contents() : null;
            languages.put(name, new CodeLanguage(name, image, command, contents));
        }
        return languages;
    }
}
