package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lists files matching a glob pattern, sorted by modification time (newest first).
 * <p>
 * Pure Java implementation using {@link PathMatcher} with {@code "glob:"} syntax —
 * no external process required. Honours the same sandboxing as
 * {@link FileReadTool} / {@link FileListTool}: paths are resolved against
 * {@code baseDir} and traversal outside it is rejected.
 * <p>
 * To stay fast and avoid drowning the model in build artefacts, a small static
 * exclude list ({@code target/}, {@code node_modules/}, {@code .git/}, …) is
 * applied while walking. Files inside excluded directories are skipped before
 * the matcher even runs.
 * <p>
 * Pattern syntax (Java NIO glob):
 * <ul>
 *   <li>{@code *.java} — Java files in the base directory</li>
 *   <li>{@code **&#47;*.java} — Java files anywhere</li>
 *   <li>{@code src/**&#47;test_*.py} — Python tests under src</li>
 *   <li>{@code **&#47;*.{ts,tsx}} — TypeScript or TSX anywhere</li>
 *   <li>{@code [Aa]gent*.java} — case-variant prefix</li>
 * </ul>
 */
public class GlobTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GlobTool.class);
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 2_000;
    /** Minimum number of file visits between progress heartbeat log lines. */
    private static final int PROGRESS_INTERVAL_ENTRIES = 5_000;
    /** Minimum wall-clock interval between progress heartbeat log lines. */
    private static final long PROGRESS_INTERVAL_MS = 5_000L;
    /** Hard wall-clock cap on the directory walk to keep tool calls bounded. */
    private static final long TIMEOUT_MS = 60_000L;

    /** Directories skipped during the walk regardless of the glob pattern. */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", ".svn", ".hg",
            "target", "build", "dist", "out", "bin",
            "node_modules", ".gradle", ".mvn",
            ".idea", ".vscode", ".settings",
            "__pycache__", ".venv", "venv", ".tox",
            ".next", ".nuxt", ".cache"
    );

    private final Path baseDir;

    public GlobTool(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Finds files by name pattern under a given directory. Returns paths newest first " +
               "(sorted by modification time). Pure path matching — does NOT read file contents; " +
               "use grep for that.\n" +
               "Both `path` and `pattern` are required. `path` is relative to the base directory; " +
               "always pass a specific project directory rather than the base directory itself, " +
               "otherwise the search will scan the entire home tree and time out.\n" +
               "Pattern examples:\n" +
               "  *.java                — Java files directly in `path`\n" +
               "  **/*.java             — Java files anywhere under `path`\n" +
               "  src/**/test_*.py      — Python tests anywhere under `path/src/`\n" +
               "  **/*.{ts,tsx,js,jsx}  — TypeScript/JavaScript files\n" +
               "Build/cache directories (target, node_modules, .git, dist, …) are skipped " +
               "automatically.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of(
                                "type", "string",
                                "description", "Glob pattern (Java NIO syntax, e.g. '**/*.java')"
                        ),
                        "path", Map.of(
                                "type", "string",
                                "description", "Sub-directory to search in (relative to base directory). " +
                                        "Required — pass a specific project directory, not the base directory itself."
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Max number of files to return. Default 200, hard cap 2000."
                        )
                ),
                "required", new String[]{"pattern", "path"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pattern = (String) arguments.get("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "Error: pattern is required";
        }
        String rawPath = ToolPaths.firstString(arguments, ToolPaths.PATH_ALIASES);
        if (rawPath == null || rawPath.equals(".")) {
            return "Error: path is required and must be a specific sub-directory (e.g. 'projects/my-app'). " +
                    "Searching from the base directory itself would scan your entire home tree.";
        }
        String relativeRoot = ToolPaths.normalise(rawPath, baseDir);
        int limit = parseLimit(arguments.get("limit"));

        Path searchRoot = baseDir.resolve(relativeRoot).normalize();
        if (!searchRoot.startsWith(baseDir)) {
            return "Error: path is outside the allowed base directory ("
                    + baseDir + "). Requested: " + rawPath;
        }
        if (!Files.exists(searchRoot)) {
            return "Error: path does not exist: " + relativeRoot
                    + suggestionsFor(searchRoot);
        }
        if (!Files.isDirectory(searchRoot)) {
            return "Error: path is not a directory: " + relativeRoot;
        }

        // Pattern is matched against paths relative to searchRoot, so the glob can
        // use simple forms like "**/*.java" without having to know the absolute path.
        PathMatcher matcher;
        try {
            matcher = searchRoot.getFileSystem().getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException e) {
            // Java's PathMatcher throws IllegalArgumentException (or its
            // PatternSyntaxException subclass) for malformed patterns.
            return "Error: invalid glob pattern '" + pattern + "': " + e.getMessage();
        }

        log.info("glob: searching '{}' for pattern '{}' (limit={}, timeout={}s)",
                baseDir.relativize(searchRoot), pattern, limit, TIMEOUT_MS / 1000);
        long walkStart = System.currentTimeMillis();
        long deadline = walkStart + TIMEOUT_MS;
        List<Match> matches = new ArrayList<>();
        // Counters need to be referenced from the FileVisitor inner class — wrap
        // them in single-element arrays so the lambda capture is "effectively final".
        int[] entries = {0};
        long[] lastHeartbeat = {walkStart};
        boolean[] timedOut = {false};
        boolean[] interrupted = {false};
        try {
            Files.walkFileTree(searchRoot, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Cooperative cancel: the ToolLoopRunner interrupts our
                    // worker thread when the user hits Stop. java.nio.file
                    // walks don't notice interrupts on their own, so we check
                    // the flag at every directory boundary.
                    if (Thread.currentThread().isInterrupted()) {
                        interrupted[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(searchRoot) && EXCLUDED_DIRS.contains(name)) {
                        log.debug("glob: skipping excluded dir {}", baseDir.relativize(dir));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // Log entries at the first level under the search root so the
                    // user can see roughly where the walk is.
                    if (dir.getParent() != null && dir.getParent().equals(searchRoot)) {
                        log.info("glob:   scanning {}/", name);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    entries[0]++;
                    Path rel = searchRoot.relativize(file);
                    if (matcher.matches(rel)) {
                        matches.add(new Match(file, attrs.lastModifiedTime().toMillis()));
                    }
                    // Heartbeat — at least every PROGRESS_INTERVAL_ENTRIES files
                    // and every PROGRESS_INTERVAL_MS ms, whichever fires first.
                    // The same throttled checkpoint is the place where we test the
                    // deadline and the interrupt flag (cheaper than calling those
                    // syscalls per file).
                    if (entries[0] % PROGRESS_INTERVAL_ENTRIES == 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            interrupted[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        long now = System.currentTimeMillis();
                        if (now >= deadline) {
                            timedOut[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (now - lastHeartbeat[0] >= PROGRESS_INTERVAL_MS) {
                            log.info("glob:   …{} entries scanned, {} matches so far ({} ms)",
                                    entries[0], matches.size(), now - walkStart);
                            lastHeartbeat[0] = now;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("glob: skipping {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "Error walking directory: " + e.getMessage();
        }
        if (interrupted[0]) {
            // Re-assert interrupt so the ToolLoopRunner / future.cancel(true)
            // path sees a properly-flagged thread for the unwind.
            Thread.currentThread().interrupt();
            log.info("glob: cancelled — scanned {} entries, {} matches before interrupt",
                    entries[0], matches.size());
            return "Glob cancelled by user after scanning " + entries[0] + " entries";
        }
        long walkMs = System.currentTimeMillis() - walkStart;
        if (timedOut[0]) {
            log.warn("glob: TIMEOUT after {} ms — scanned {} entries, {} matches so far",
                    walkMs, entries[0], matches.size());
        } else {
            log.info("glob: done — scanned {} entries in {} ms, {} matches",
                    entries[0], walkMs, matches.size());
        }

        if (matches.isEmpty()) {
            String msg = "No files matched pattern '" + pattern + "' in " + relativeRoot;
            if (timedOut[0]) {
                msg += "\n[Search timed out after " + (TIMEOUT_MS / 1000) +
                        "s — narrow `path` to a smaller sub-directory.]";
            }
            return msg;
        }

        // Sort newest first (most relevant for an LLM browsing recent activity).
        matches.sort(Comparator.comparingLong(Match::mtime).reversed());
        int total = matches.size();
        List<Match> shown = matches.size() > limit ? matches.subList(0, limit) : matches;

        StringBuilder sb = new StringBuilder();
        sb.append("Pattern: ").append(pattern).append('\n');
        sb.append("Search root: ").append(searchRoot).append('\n');
        if (timedOut[0]) {
            sb.append("⚠ Search timed out after ").append(TIMEOUT_MS / 1000)
              .append("s — results below are partial. Narrow `path` to a smaller sub-directory.\n");
        }
        sb.append("Matches: ").append(total);
        if (total > shown.size()) {
            sb.append(" (showing newest ").append(shown.size()).append(")");
        }
        sb.append('\n');
        for (Match m : shown) {
            // Render paths relative to baseDir so they're stable across invocations.
            sb.append(baseDir.relativize(m.path()).toString().replace('\\', '/')).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * When a `path` doesn't exist, list the closest sibling directory names so the
     * agent can self-correct in the next call. Walks upward until it finds a parent
     * that does exist (typical case: user typed "Google Drive" but the actual folder
     * is "Google Drive (foo@gmail.com)" — the parent is `baseDir`, listing its
     * children produces the right suggestion).
     * <p>
     * Returns a multi-line block beginning with {@code "\n"} and ending with the
     * top-3 closest names by Levenshtein distance, or an empty string if the parent
     * itself can't be read.
     */
    private String suggestionsFor(Path missing) {
        Path parent = missing.getParent();
        // Walk up until we find a parent that exists and is inside baseDir.
        while (parent != null && parent.startsWith(baseDir) && !Files.isDirectory(parent)) {
            parent = parent.getParent();
        }
        if (parent == null || !parent.startsWith(baseDir) || !Files.isDirectory(parent)) {
            return "";
        }
        String missingName = missing.getFileName() != null ? missing.getFileName().toString() : "";
        if (missingName.isEmpty()) return "";

        List<String> children = new ArrayList<>();
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isDirectory)
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        if (!EXCLUDED_DIRS.contains(name)) children.add(name);
                    });
        } catch (IOException e) {
            return "";
        }
        if (children.isEmpty()) return "";

        // Score each child by similarity to the missing segment (case-insensitive),
        // pick top 3.
        String needle = missingName.toLowerCase();
        children.sort(Comparator.comparingInt(c -> similarityScore(needle, c.toLowerCase())));
        int top = Math.min(3, children.size());
        StringBuilder sb = new StringBuilder("\nDid you mean one of these (in '");
        sb.append(baseDir.relativize(parent)).append("')?\n");
        for (int i = 0; i < top; i++) {
            sb.append("  - ").append(children.get(i)).append('/').append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Similarity score, lower is better. Combines:
     *  - Levenshtein edit distance
     *  - Bonus (negative score offset) when one is a substring of the other —
     *    matches the common case "Google Drive" vs "Google Drive (mail@x.com)".
     */
    private static int similarityScore(String needle, String candidate) {
        int distance = levenshtein(needle, candidate);
        if (candidate.contains(needle) || needle.contains(candidate)) {
            distance -= 50;  // strong preference for substring matches
        }
        return distance;
    }

    private static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private static int parseLimit(Object raw) {
        if (raw == null) return DEFAULT_LIMIT;
        try {
            int v = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(raw.toString());
            if (v <= 0) return DEFAULT_LIMIT;
            return Math.min(v, MAX_LIMIT);
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private record Match(Path path, long mtime) {}
}
