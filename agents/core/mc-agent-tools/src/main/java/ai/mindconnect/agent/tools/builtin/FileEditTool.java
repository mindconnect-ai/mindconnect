package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Changes one passage of a file: the text in {@code old_string} becomes
 * {@code new_string}. The passage has to be unique — an ambiguous match is
 * refused with a count, and the model adds context until it is — unless
 * {@code replace_all} asks for every occurrence. What changed comes back
 * as a unified diff, so the model sees the edit it made, not the file.
 */
public class FileEditTool implements Tool {

    /** Lines of context around a change in the diff. */
    private static final int CONTEXT = 3;
    /** Above this many lines the diff is not computed line by line; the result says what was replaced instead. */
    private static final int MAX_DIFF_LINES = 4_000;

    private final Path baseDir;
    private final FileRoots roots;

    public FileEditTool(Path baseDir) {
        this(FileRoots.of(baseDir));
    }

    /** Rooted at the session's directories — the base for relative paths, the rest by absolute path. */
    public FileEditTool(FileRoots roots) {
        this.roots = roots;
        this.baseDir = roots.base();
    }

    @Override
    public String name() {
        return "file_edit";
    }

    @Override
    public String description() {
        return "Replaces one passage of an existing text file: `old_string` becomes `new_string`. "
                + "`old_string` must match the file exactly — indentation and line breaks included — and "
                + "must occur exactly once; include enough surrounding lines to make it unique, or pass "
                + "`replace_all` to change every occurrence. Read the file first (file_read) so the passage "
                + "is what the file really says. Returns a unified diff of the change. For a new file or "
                + "a whole-file rewrite use file_write. Paths are relative to the working directory "
                + baseDir + " or absolute into one of the session's directories.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Path of the file to change, relative to the working directory or absolute"
                        ),
                        "old_string", Map.of(
                                "type", "string",
                                "description", "The exact text to replace — unique in the file unless replace_all"
                        ),
                        "new_string", Map.of(
                                "type", "string",
                                "description", "The text that takes its place (empty deletes the passage)"
                        ),
                        "replace_all", Map.of(
                                "type", "boolean",
                                "description", "Replace every occurrence instead of requiring a unique one. Default false."
                        )
                ),
                "required", new String[]{"path", "old_string", "new_string"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String raw = ToolPaths.firstString(arguments, ToolPaths.PATH_ALIASES);
        if (raw == null) {
            return ToolPaths.missingArgError("path", ToolPaths.PATH_ALIASES, arguments);
        }
        Object oldArg = arguments.get("old_string");
        Object newArg = arguments.get("new_string");
        if (oldArg == null || oldArg.toString().isEmpty()) {
            return "Error: old_string is required and must not be empty — name the passage to replace.";
        }
        if (newArg == null) {
            return "Error: new_string is required (an empty string deletes the passage).";
        }
        String oldString = oldArg.toString();
        String newString = newArg.toString();
        if (oldString.equals(newString)) {
            return "Error: old_string and new_string are the same — nothing to change.";
        }
        boolean replaceAll = Boolean.parseBoolean(String.valueOf(arguments.getOrDefault("replace_all", "false")));

        String relative = ToolPaths.normalise(raw, baseDir);
        Path target = roots.resolve(relative).orElse(null);
        if (target == null) {
            return roots.outsideError(raw);
        }
        if (!Files.exists(target)) {
            return "Error: file does not exist: " + relative + " — use file_write to create a file.";
        }
        if (Files.isDirectory(target)) {
            return "Error: path is a directory: " + relative;
        }
        if (FileWalks.isBinary(target)) {
            return "Error: " + relative + " is a binary file.";
        }
        String content;
        Charset charset;
        try {
            FileWalks.Decoded decoded = FileWalks.readText(target);
            content = decoded.text();
            charset = decoded.charset();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
        // A Windows file keeps its CRLF: the model writes \n, the passage is
        // looked for and written back with the file's own line separator.
        if (content.contains("\r\n")) {
            oldString = crlf(oldString);
            newString = crlf(newString);
        }

        int occurrences = count(content, oldString);
        String changed;
        if (occurrences == 0) {
            // Not there as written. The usual reason is indentation the model
            // got wrong — try the passage with whitespace differences forgiven.
            Tolerant tolerant = Tolerant.find(content, oldString);
            if (tolerant == null) {
                return "Error: old_string was not found in " + relative + ". It must match the file exactly, "
                        + "whitespace and line breaks included — read the file with file_read and copy the passage."
                        + closestPassage(lf(content), lf(oldString));
            }
            if (tolerant.count() > 1 && !replaceAll) {
                return "Error: old_string (with whitespace differences forgiven) occurs " + tolerant.count()
                        + " times in " + relative + ". Include more of the surrounding lines so it is unique.";
            }
            changed = tolerant.apply(content, newString, replaceAll);
            occurrences = tolerant.count();
        } else {
            if (occurrences > 1 && !replaceAll) {
                return "Error: old_string occurs " + occurrences + " times in " + relative + ". Include more of "
                        + "the surrounding lines so it is unique, or pass replace_all=true to change every occurrence.";
            }
            changed = replaceAll ? content.replace(oldString, newString)
                    : content.replaceFirst(java.util.regex.Pattern.quote(oldString),
                            java.util.regex.Matcher.quoteReplacement(newString));
        }
        if (!charset.newEncoder().canEncode(changed)) {
            return "Error: " + relative + " is " + charset.name() + " text and new_string has characters that "
                    + "encoding cannot hold. Use only characters the file's encoding has.";
        }
        try {
            // Written back in the encoding it was read in: a Latin-1 file stays Latin-1.
            Files.writeString(target, changed, charset);
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
        String header = (replaceAll && occurrences > 1 ? "Replaced " + occurrences + " occurrences in " : "Edited ")
                + roots.display(target) + "\n";
        return header + unifiedDiff(roots.display(target), lf(content), lf(changed));
    }

    /** {@code text} with every line break as CRLF. */
    private static String crlf(String text) {
        return lf(text).replace("\n", "\r\n");
    }

    /** {@code text} with every CRLF as a bare line feed. */
    private static String lf(String text) {
        return text.replace("\r\n", "\n");
    }

    /**
     * When the passage is not in the file: the lines that come closest to
     * its first line, numbered, so the model copies what is really there
     * instead of trying the same guess again.
     */
    static String closestPassage(String content, String oldString) {
        List<String> lines = lines(content);
        List<String> wanted = lines(oldString).stream().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty() || wanted.isEmpty()) return "";
        String first = wanted.get(0).strip();
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < lines.size(); i++) {
            double score = similarity(first, lines.get(i).strip());
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0 || bestScore < 0.5) return "";
        int from = Math.max(0, best - 2);
        int to = Math.min(lines.size(), best + Math.max(wanted.size(), 1) + 2);
        StringBuilder out = new StringBuilder("\nThe closest passage in the file (lines ")
                .append(from + 1).append('-').append(to).append("):\n");
        int width = String.valueOf(to).length();
        for (int i = from; i < to; i++) {
            out.append(String.format("%" + width + "d\t%s%n", i + 1, lines.get(i)));
        }
        return out.toString().stripTrailing();
    }

    /** 1 for equal strings, down to 0 — a cheap ratio on the longest common prefix and suffix plus shared tokens. */
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (a.contains(b) || b.contains(a)) return 0.8;
        java.util.Set<String> ta = new java.util.HashSet<>(java.util.Arrays.asList(a.split("\\W+")));
        java.util.Set<String> tb = new java.util.HashSet<>(java.util.Arrays.asList(b.split("\\W+")));
        ta.remove(""); tb.remove("");
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        long common = ta.stream().filter(tb::contains).count();
        return (double) common / Math.max(ta.size(), tb.size());
    }

    /**
     * A match of the passage with whitespace forgiven: every line compared
     * with leading and trailing whitespace stripped, the file's own
     * indentation kept on the replacement. Only whole lines — a passage that
     * starts mid-line has no indentation to get wrong. Occurrences do not
     * overlap, as with an exact match; a file with CRLF line breaks is
     * compared without them and written back with CRLF throughout.
     */
    record Tolerant(List<String> fileLines, List<String> oldLines, List<Integer> starts, boolean trailingNewline,
                    String lineSeparator) {
        int count() {
            return starts.size();
        }

        static Tolerant find(String content, String oldString) {
            List<String> fileLines = lines(lf(content));
            List<String> oldLines = lines(lf(oldString));
            while (!oldLines.isEmpty() && oldLines.get(oldLines.size() - 1).isBlank()) {
                oldLines = oldLines.subList(0, oldLines.size() - 1);
            }
            if (oldLines.isEmpty() || fileLines.size() < oldLines.size()) return null;
            List<Integer> starts = new ArrayList<>();
            outer:
            for (int i = 0; i + oldLines.size() <= fileLines.size(); i++) {
                for (int k = 0; k < oldLines.size(); k++) {
                    if (!fileLines.get(i + k).strip().equals(oldLines.get(k).strip())) continue outer;
                }
                starts.add(i);
                i += oldLines.size() - 1; // the next occurrence starts after this one
            }
            return starts.isEmpty() ? null : new Tolerant(fileLines, oldLines, starts, content.endsWith("\n"),
                    content.contains("\r\n") ? "\r\n" : "\n");
        }

        String apply(String content, String newString, boolean all) {
            List<String> out = new ArrayList<>();
            List<Integer> use = all ? starts : starts.subList(0, 1);
            int i = 0;
            for (int start : use) {
                while (i < start) out.add(fileLines.get(i++));
                out.addAll(reindented(lf(newString), fileLines.subList(start, start + oldLines.size()), oldLines));
                i += oldLines.size();
            }
            while (i < fileLines.size()) out.add(fileLines.get(i++));
            return String.join(lineSeparator, out) + (trailingNewline ? lineSeparator : "");
        }

        /**
         * The replacement in the file's indentation. The passage's lines and
         * the file's correspond one to one, which says how the model's
         * indentation maps to the file's — two spaces for four, none for
         * eight — and every replacement line is mapped the same way; an
         * unseen depth continues the pattern from the nearest seen one.
         */
        private static List<String> reindented(String newString, List<String> fileSpan, List<String> oldSpan) {
            java.util.TreeMap<Integer, String> map = new java.util.TreeMap<>();
            for (int k = 0; k < oldSpan.size(); k++) {
                if (oldSpan.get(k).isBlank()) continue;
                map.putIfAbsent(leading(oldSpan.get(k)).length(), leading(fileSpan.get(k)));
            }
            List<String> lines = lines(newString);
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines = lines.subList(0, lines.size() - 1);
            List<String> out = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank()) { out.add(""); continue; }
                String indent = leading(line);
                out.add(mapped(map, indent) + line.substring(indent.length()));
            }
            return out;
        }

        /** The file's indentation for one of the passage's depths, or the nearest one continued by the same step. */
        private static String mapped(java.util.TreeMap<Integer, String> map, String indent) {
            int n = indent.length();
            if (map.isEmpty()) return indent;
            String exact = map.get(n);
            if (exact != null) return exact;
            var floor = map.floorEntry(n);
            var ceil = map.ceilingEntry(n);
            if (floor == null) {
                // Shallower than anything seen: shrink by the same ratio.
                return ceil.getValue().substring(0, Math.min(ceil.getValue().length(),
                        n * ceil.getValue().length() / Math.max(1, ceil.getKey())));
            }
            int unitOld = step(map.keySet());
            int unitFile = step(map.values().stream().map(String::length).toList());
            int extra = (n - floor.getKey()) * unitFile / Math.max(1, unitOld);
            return floor.getValue() + " ".repeat(Math.max(0, extra));
        }

        /** The smallest positive difference between depths — the indentation unit — or 1. */
        private static int step(java.util.Collection<Integer> depths) {
            var sorted = new java.util.TreeSet<>(depths);
            int best = 0;
            Integer prev = null;
            for (int d : sorted) {
                if (prev != null && d - prev > 0 && (best == 0 || d - prev < best)) best = d - prev;
                prev = d;
            }
            return best == 0 ? 1 : best;
        }

        private static String leading(String s) {
            int i = 0;
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            return s.substring(0, i);
        }
    }

    static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * A unified diff of {@code before} → {@code after}, hunks with
     * {@link #CONTEXT} lines around each change. Line-based LCS; a file too
     * long for that gets a one-line summary instead.
     */
    static String unifiedDiff(String name, String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        if (a.size() > MAX_DIFF_LINES || b.size() > MAX_DIFF_LINES) {
            return "--- " + name + "\n+++ " + name + "\n(file too long for a diff: "
                    + a.size() + " → " + b.size() + " lines)";
        }
        // ops: for each step, 'k' keep a[i]/b[j], 'd' delete a[i], 'i' insert b[j]
        int n = a.size(), m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<char[]> ops = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                ops.add(new char[]{'k'}); texts.add(a.get(i)); i++; j++;
            } else if (i < n && (j >= m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                // Deletions before insertions, the way diff prints a change.
                ops.add(new char[]{'d'}); texts.add(a.get(i)); i++;
            } else {
                ops.add(new char[]{'i'}); texts.add(b.get(j)); j++;
            }
        }
        StringBuilder out = new StringBuilder("--- ").append(name).append("\n+++ ").append(name).append('\n');
        int pos = 0;
        int aLine = 1, bLine = 1; // 1-based line counters at position pos
        while (pos < ops.size()) {
            // find next change
            while (pos < ops.size() && ops.get(pos)[0] == 'k') { pos++; aLine++; bLine++; }
            if (pos >= ops.size()) break;
            int start = Math.max(0, pos - CONTEXT);
            // hunk end: last change such that the gap of keeps between changes is <= 2*CONTEXT
            int end = pos;
            int scan = pos;
            while (scan < ops.size()) {
                if (ops.get(scan)[0] != 'k') { end = scan; scan++; continue; }
                int gap = 0;
                while (scan < ops.size() && ops.get(scan)[0] == 'k') { gap++; scan++; }
                if (gap > 2 * CONTEXT || scan >= ops.size()) break;
            }
            int hunkEnd = Math.min(ops.size(), end + 1 + CONTEXT);
            // line numbers at 'start'
            int aStart = aLine - (pos - start), bStart = bLine - (pos - start);
            int aCount = 0, bCount = 0;
            StringBuilder body = new StringBuilder();
            for (int k = start; k < hunkEnd; k++) {
                char op = ops.get(k)[0];
                String t = texts.get(k);
                switch (op) {
                    case 'k' -> { body.append(' ').append(t).append('\n'); aCount++; bCount++; }
                    case 'd' -> { body.append('-').append(t).append('\n'); aCount++; }
                    default -> { body.append('+').append(t).append('\n'); bCount++; }
                }
            }
            out.append("@@ -").append(aStart).append(',').append(aCount)
                    .append(" +").append(bStart).append(',').append(bCount).append(" @@\n").append(body);
            // advance counters to hunkEnd
            for (int k = pos; k < hunkEnd; k++) {
                char op = ops.get(k)[0];
                if (op != 'i') aLine++;
                if (op != 'd') bLine++;
            }
            pos = hunkEnd;
        }
        return out.toString().stripTrailing();
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        for (String line : text.split("\n", -1)) out.add(line);
        if (text.endsWith("\n")) out.remove(out.size() - 1);
        return out;
    }
}
