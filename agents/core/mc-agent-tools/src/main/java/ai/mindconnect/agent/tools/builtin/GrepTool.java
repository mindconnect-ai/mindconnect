package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Searches file contents: a regular expression over every text file under
 * a directory (or one file), the way {@code grep -rn} does, with an
 * optional name filter, case folding and context lines. Binary files and
 * build directories are skipped, results are capped, and the walk is
 * bounded in time. Rooted at the session's directories like every file
 * tool.
 */
public class GrepTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1_000;
    /** A file bigger than this is skipped — logs and data dumps, not source. */
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** A matching line longer than this is cut in the output. */
    static final int MAX_LINE_CHARS = 500;
    static final int MAX_CONTEXT = 10;
    private static final long TIMEOUT_MS = 60_000L;

    private final Path baseDir;
    private final FileRoots roots;

    public GrepTool(Path baseDir) {
        this(FileRoots.of(baseDir));
    }

    /** Rooted at the session's directories — the base for relative paths, the rest by absolute path. */
    public GrepTool(FileRoots roots) {
        this.roots = roots;
        this.baseDir = roots.base();
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Searches file contents for a regular expression, like `grep -rn`: every text file under "
                + "`path` (a directory, default the working directory " + baseDir + ", or a single file), "
                + "returning `file:line: text` for each match, newest files first. `glob` narrows the files "
                + "by name (`*.java`, `**/*.{ts,tsx}`), `context` adds lines around each match, "
                + "`ignore_case` folds case, `files_only` lists just the files that match. Binary files "
                + "and build directories (target, node_modules, .git, …) are skipped. Java regex syntax; "
                + "escape literal dots and brackets. To find files by name use glob instead.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of("type", "string",
                "description", "Regular expression to search for (Java syntax)"));
        props.put("path", Map.of("type", "string",
                "description", "Directory or file to search, relative to the working directory or absolute. Default: the working directory."));
        props.put("glob", Map.of("type", "string",
                "description", "Only files whose path matches this glob, e.g. '*.java' or '**/*.{ts,tsx}'. Optional."));
        props.put("ignore_case", Map.of("type", "boolean",
                "description", "Case-insensitive match. Default false."));
        props.put("context", Map.of("type", "integer",
                "description", "Lines of context before and after each match (0-" + MAX_CONTEXT + "). Default 0."));
        props.put("files_only", Map.of("type", "boolean",
                "description", "List only the files that contain a match, without the lines. Default false."));
        props.put("limit", Map.of("type", "integer",
                "description", "Max matches to return. Default " + DEFAULT_LIMIT + ", hard cap " + MAX_LIMIT + "."));
        return Map.of("type", "object", "properties", props, "required", new String[]{"pattern"});
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String patternText = (String) arguments.get("pattern");
        if (patternText == null || patternText.isBlank()) {
            return "Error: pattern is required";
        }
        boolean ignoreCase = bool(arguments.get("ignore_case"));
        boolean filesOnly = bool(arguments.get("files_only"));
        int context = Math.max(0, Math.min(MAX_CONTEXT, intArg(arguments.get("context"), 0)));
        int limit = intArg(arguments.get("limit"), DEFAULT_LIMIT);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regular expression '" + patternText + "': " + e.getDescription();
        }
        String rawPath = ToolPaths.firstString(arguments, ToolPaths.PATH_ALIASES);
        if (rawPath == null || rawPath.isBlank()) rawPath = ".";
        String relative = ToolPaths.normalise(rawPath, baseDir);
        Path start = roots.resolve(relative).orElse(null);
        if (start == null) {
            return roots.outsideError(rawPath);
        }
        if (!Files.exists(start)) {
            return "Error: path does not exist: " + relative;
        }
        PathMatcher nameFilter = null;
        Object globArg = arguments.get("glob");
        if (globArg != null && !globArg.toString().isBlank()) {
            String glob = globArg.toString().trim();
            try {
                // A bare name pattern applies at any depth, as grep --include does.
                nameFilter = start.getFileSystem().getPathMatcher(
                        "glob:" + (glob.contains("/") ? glob : "{**/,}" + glob));
            } catch (IllegalArgumentException e) {
                return "Error: invalid glob '" + glob + "': " + e.getMessage();
            }
        }

        List<Path> files = new ArrayList<>();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        boolean[] timedOut = {false};
        if (Files.isDirectory(start)) {
            PathMatcher filter = nameFilter;
            try {
                Files.walkFileTree(start, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() > deadline) {
                            timedOut[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        return !dir.equals(start) && FileWalks.EXCLUDED_DIRS.contains(name)
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_BYTES
                                && (filter == null || filter.matches(start.relativize(file)))) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return "Error walking directory: " + e.getMessage();
            }
        } else {
            files.add(start);
        }
        // Newest first, like glob: the file being worked on is what the caller means.
        files.sort((x, y) -> Long.compare(mtime(y), mtime(x)));

        StringBuilder out = new StringBuilder();
        int matches = 0;
        int filesWithMatches = 0;
        boolean capped = false;
        outer:
        for (Path file : files) {
            if (System.currentTimeMillis() > deadline || Thread.currentThread().isInterrupted()) {
                timedOut[0] = true;
                break;
            }
            if (FileWalks.isBinary(file)) continue;
            List<String> lines;
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                lines = reader.lines().toList();
            } catch (IOException | java.io.UncheckedIOException e) {
                continue; // not UTF-8, not ours
            }
            boolean any = false;
            int lastPrinted = -1;
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = pattern.matcher(lines.get(i));
                if (!m.find()) continue;
                if (!any) {
                    filesWithMatches++;
                    any = true;
                    if (filesOnly) {
                        out.append(roots.display(file)).append('\n');
                        matches++;
                        if (matches >= limit) { capped = true; break outer; }
                        break;
                    }
                }
                String shown = roots.display(file);
                if (context > 0) {
                    if (lastPrinted >= 0 && i - context > lastPrinted + 1) out.append("--\n");
                    for (int c = Math.max(lastPrinted + 1, i - context); c < i; c++) {
                        out.append(shown).append('-').append(c + 1).append("- ").append(cut(lines.get(c))).append('\n');
                    }
                }
                out.append(shown).append(':').append(i + 1).append(": ").append(cut(lines.get(i))).append('\n');
                lastPrinted = i;
                if (context > 0) {
                    for (int c = i + 1; c <= Math.min(lines.size() - 1, i + context); c++) {
                        out.append(shown).append('-').append(c + 1).append("- ").append(cut(lines.get(c))).append('\n');
                        lastPrinted = c;
                    }
                }
                matches++;
                if (matches >= limit) { capped = true; break outer; }
            }
        }
        log.info("grep: '{}' in {} — {} match(es) in {} file(s){}", patternText, roots.display(start),
                matches, filesWithMatches, timedOut[0] ? " (timed out)" : "");
        if (matches == 0) {
            return "No matches for '" + patternText + "' in " + relative
                    + (timedOut[0] ? "\n[Search timed out after " + TIMEOUT_MS / 1000 + "s — narrow path or glob.]" : "");
        }
        StringBuilder head = new StringBuilder();
        head.append(filesOnly ? "Files with matches: " : "Matches: ").append(matches);
        if (!filesOnly) head.append(" in ").append(filesWithMatches).append(" file(s)");
        if (capped) head.append(" (stopped at ").append(limit).append(" — narrow the pattern, path or glob, or raise limit)");
        if (timedOut[0]) head.append(" — search timed out after ").append(TIMEOUT_MS / 1000).append("s, results are partial");
        return head.append('\n').append(out).toString().stripTrailing();
    }

    private static String cut(String line) {
        return line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + " …" : line;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean bool(Object raw) {
        return raw != null && Boolean.parseBoolean(raw.toString().trim());
    }

    private static int intArg(Object raw, int fallback) {
        if (raw == null) return fallback;
        try {
            return raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
