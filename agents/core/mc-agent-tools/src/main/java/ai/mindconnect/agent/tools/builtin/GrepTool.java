package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.workspace.WorkspaceEntry;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tool.workspace.WorkspaceWalker;
import ai.mindconnect.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    static final long TIMEOUT_MS = 60_000L;

    private final Path baseDir;
    private final FileRoots roots;
    private final WorkspaceFiles files;
    private final long timeoutMs;

    public GrepTool(Path baseDir) {
        this(FileRoots.of(baseDir));
    }

    /** Rooted at the session's directories — the base for relative paths, the rest by absolute path. */
    public GrepTool(FileRoots roots) {
        this(roots, TIMEOUT_MS);
    }

    /** With a time budget of its own — for tests that should not wait a minute. */
    GrepTool(FileRoots roots, long timeoutMs) {
        this(WorkspaceFiles.local(roots), timeoutMs);
    }

    /** Working on {@code files}: this machine's, or a workspace that lives elsewhere. */
    public GrepTool(WorkspaceFiles files) {
        this(files, TIMEOUT_MS);
    }

    GrepTool(WorkspaceFiles files, long timeoutMs) {
        this.files = files;
        this.roots = files.roots();
        this.baseDir = roots.base();
        this.timeoutMs = timeoutMs;
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
        if (!files.exists(start)) {
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

        // Entries, not paths: the walk already knows each file's time, so sorting costs no second look.
        List<WorkspaceEntry> candidates = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean[] timedOut = {false};
        if (files.isDirectory(start)) {
            PathMatcher filter = nameFilter;
            try {
                files.walk(start, FileWalks.EXCLUDED_DIRS, new WorkspaceWalker() {
                    @Override
                    public Step directory(WorkspaceEntry dir) {
                        if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() > deadline) {
                            timedOut[0] = true;
                            return Step.TERMINATE;
                        }
                        return Step.CONTINUE;
                    }

                    @Override
                    public Step file(WorkspaceEntry file) {
                        if (file.regularFile() && file.size() <= MAX_FILE_BYTES
                                && (filter == null || filter.matches(start.relativize(file.path())))) {
                            candidates.add(file);
                        }
                        return Step.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return "Error walking directory: " + e.getMessage();
            }
        } else {
            try {
                files.stat(start).ifPresent(candidates::add);
            } catch (IOException e) {
                return "Error reading file: " + e.getMessage();
            }
        }
        // Newest first, like glob: the file being worked on is what the caller means.
        candidates.sort((x, y) -> Long.compare(y.lastModifiedMillis(), x.lastModifiedMillis()));

        StringBuilder out = new StringBuilder();
        int matches = 0;
        int filesWithMatches = 0;
        boolean capped = false;
        outer:
        for (WorkspaceEntry candidate : candidates) {
            Path file = candidate.path();
            if (System.currentTimeMillis() > deadline || Thread.currentThread().isInterrupted()) {
                timedOut[0] = true;
                break;
            }
            List<String> lines;
            try {
                // One read per file: the head decides binary, the rest is the text.
                byte[] bytes = files.readAllBytes(file);
                if (FileWalks.isBinary(Arrays.copyOf(bytes, Math.min(bytes.length, FileWalks.SNIFF_BYTES)))) {
                    continue;
                }
                lines = FileWalks.decode(bytes).text().lines().toList();
            } catch (IOException e) {
                continue; // unreadable, not ours
            }
            boolean any = false;
            int lastPrinted = -1;
            for (int i = 0; i < lines.size(); i++) {
                // A backtracking pattern can spend minutes on one line: the
                // line itself watches the clock while the matcher reads it.
                boolean found;
                try {
                    found = pattern.matcher(new TimedText(lines.get(i), deadline)).find();
                } catch (TimeUp e) {
                    timedOut[0] = true;
                    break outer;
                }
                if (!found) continue;
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
                    + (timedOut[0] ? "\n[Search timed out after " + timeoutText()
                    + " — narrow path or glob, or simplify the pattern.]" : "");
        }
        StringBuilder head = new StringBuilder();
        head.append(filesOnly ? "Files with matches: " : "Matches: ").append(matches);
        if (!filesOnly) head.append(" in ").append(filesWithMatches).append(" file(s)");
        if (capped) head.append(" (stopped at ").append(limit).append(" — narrow the pattern, path or glob, or raise limit)");
        if (timedOut[0]) head.append(" — search timed out after ").append(timeoutText()).append(", results are partial");
        return head.append('\n').append(out).toString().stripTrailing();
    }

    private String timeoutText() {
        return timeoutMs >= 1000 ? timeoutMs / 1000 + "s" : timeoutMs + "ms";
    }

    /** Thrown out of the matcher when the search's time is up or its thread is interrupted. */
    private static final class TimeUp extends RuntimeException {
        TimeUp() {
            super(null, null, false, false);
        }
    }

    /**
     * A line that checks the deadline and the interrupt flag while the
     * matcher reads it — every few thousand characters, so a pattern that
     * backtracks without end is stopped mid-match instead of after it.
     */
    private static final class TimedText implements CharSequence {
        private static final int CHECK_EVERY = 4_096;
        private final CharSequence text;
        private final long deadline;
        private int reads;

        TimedText(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (++reads >= CHECK_EVERY) {
                reads = 0;
                if (System.currentTimeMillis() > deadline || Thread.currentThread().isInterrupted()) {
                    throw new TimeUp();
                }
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new TimedText(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    private static String cut(String line) {
        return line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + " …" : line;
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
