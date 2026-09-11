package ai.mindconnect.agent.runtime.service.agents;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Sub-agents a project brings with it, in {@code .mindconnect/agents/} in the
 * session's working directory. A reviewer that knows this codebase's rules,
 * a migration writer that knows its schema: worth keeping beside the code
 * rather than in a registry every project shares.
 *
 * <p>One Markdown file per agent, the same shape a couple of other coding
 * tools already use, so a definition can be moved between them:
 *
 * <pre>
 * ---
 * name: verifier
 * description: Checks that a change really builds and its tests pass
 * tools: bash, file_read, grep
 * model: claude-haiku-default
 * ---
 * You verify. Run the build and the tests, then say what you ran and what
 * came back. Never report success you have not seen.
 * </pre>
 *
 * <p>Everything but the body is optional; {@code name} defaults to the file
 * name. The body is the system prompt.
 *
 * <p><b>Tools are filtered, never granted.</b> A project agent may only use
 * what the agent calling it already has, and each tool keeps the approval
 * the caller's binding gives it. Opening someone else's repository therefore
 * cannot hand it a shell it was not already going to get: the file narrows
 * the caller's own tools, it cannot widen them.
 */
public final class ProjectAgents {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgents.class);

    /** Where a project keeps them, relative to the working directory. */
    public static final String DIR = ".mindconnect/agents";

    private static final String SUFFIX = ".md";

    /** A definition longer than this is a document, not an agent. */
    static final int MAX_CHARS = 100_000;

    private ProjectAgents() {}

    /** The agent of that name the project defines, if it defines one. */
    public static Optional<ProjectAgent> find(String workingDir, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return list(workingDir).stream()
                .filter(a -> a.name().equalsIgnoreCase(name.strip()))
                .findFirst();
    }

    /** Every agent the project defines, in file-name order; empty when it defines none. */
    public static List<ProjectAgent> list(String workingDir) {
        Path dir = agentsDir(workingDir);
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SUFFIX))
                    .sorted()
                    .map(ProjectAgents::parseFile)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException | RuntimeException e) {
            log.debug("Project agents in {} could not be listed: {}", dir, e.toString());
            return List.of();
        }
    }

    /** {@code <workingDir>/.mindconnect/agents}, or {@code null} when there is no working directory. */
    static Path agentsDir(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) return null;
        try {
            return Path.of(workingDir).resolve(DIR);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static ProjectAgent parseFile(Path file) {
        try {
            if (Files.size(file) > MAX_CHARS) {
                log.debug("Project agent {} is too large to read", file);
                return null;
            }
            String fileName = file.getFileName().toString();
            return parse(fileName.substring(0, fileName.length() - SUFFIX.length()),
                    Files.readString(file));
        } catch (IOException | RuntimeException e) {
            log.debug("Project agent {} could not be read: {}", file, e.toString());
            return null;
        }
    }

    /**
     * One file's content: the front matter as fields, the rest as the system
     * prompt. {@code null} when there is no prompt to run on.
     */
    static ProjectAgent parse(String fileName, String content) {
        Map<String, String> head = Map.of();
        String body = content;
        String text = content.stripLeading();
        if (text.startsWith("---")) {
            int firstBreak = text.indexOf('\n');
            int close = firstBreak < 0 ? -1 : indexOfClosingFence(text, firstBreak + 1);
            if (close >= 0) {
                Map<String, String> fields = frontMatter(text.substring(firstBreak + 1, close));
                // A pair of --- lines with no field between them is not front
                // matter, it is a horizontal rule opening the prompt. Taking
                // it for front matter would swallow the lines in between.
                if (!fields.isEmpty()) {
                    head = fields;
                    int afterFence = text.indexOf('\n', close);
                    body = afterFence < 0 ? "" : text.substring(afterFence + 1);
                }
            }
        }
        String prompt = body.strip();
        if (prompt.isEmpty()) return null;
        String name = head.getOrDefault("name", fileName).strip();
        if (name.isEmpty()) name = fileName;
        return new ProjectAgent(name, head.getOrDefault("description", ""), prompt,
                listOf(head.get("tools")), listOf(head.get("disallowedtools")),
                blankToNull(head.get("model")));
    }


    /**
     * The {@code key: value} lines of a front-matter block, keys lowercased.
     *
     * <p>A value may also arrive as an indented block sequence, which is what
     * anyone writing YAML reaches for:
     *
     * <pre>
     * tools:
     *   - file_read
     *   - grep
     * </pre>
     *
     * Those items are joined into the comma form, so the flow list and the
     * block list mean the same thing. They have to: reading the block form as
     * an empty value would leave the agent with every tool its caller has,
     * which is the opposite of what a file naming two tools asks for.
     */
    private static Map<String, String> frontMatter(String block) {
        Map<String, String> head = new LinkedHashMap<>();
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (value.isEmpty()) {
                List<String> items = new ArrayList<>();
                while (i + 1 < lines.length && isSequenceItem(lines[i + 1])) {
                    items.add(lines[++i].stripLeading().substring(1).strip());
                }
                value = String.join(", ", items);
            }
            head.put(key, unquote(value));
        }
        return head;
    }

    /** A {@code - item} line of a block sequence, indented or not. */
    private static boolean isSequenceItem(String line) {
        String stripped = line.stripLeading();
        return stripped.length() > 1 && stripped.charAt(0) == '-'
                && Character.isWhitespace(stripped.charAt(1));
    }

    /** The line index where a lone {@code ---} closes the front matter, or -1. */
    private static int indexOfClosingFence(String text, int from) {
        int at = from;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            String line = (end < 0 ? text.substring(at) : text.substring(at, end)).strip();
            if (line.equals("---")) return at;
            if (end < 0) return -1;
            at = end + 1;
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() > 1
                && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static List<String> listOf(String value) {
        if (value == null || value.isBlank()) return List.of();
        String inner = value.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        return Arrays.stream(inner.split(","))
                .map(String::strip)
                .map(ProjectAgents::unquote)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** An agent a project defines beside its code. */
    public record ProjectAgent(String name,
                               String description,
                               String systemPrompt,
                               List<String> tools,
                               List<String> disallowedTools,
                               String model) {

        public ProjectAgent {
            tools = tools == null ? List.of() : List.copyOf(tools);
            disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        }

        /**
         * The tools this agent may use: the caller's own, narrowed by the
         * file. {@code disallowedTools} is taken away first, then
         * {@code tools} keeps what it names; naming none keeps everything the
         * caller has. A name the caller does not have is not an error and not
         * a grant — it simply matches nothing.
         *
         * <p>Each tool keeps the caller's binding, the approval flag and the
         * result cap included, under an id of its own.
         */
        public List<AgentTool> toolsFrom(List<AgentTool> callerTools) {
            if (callerTools == null) return List.of();
            List<AgentTool> out = new ArrayList<>();
            for (AgentTool tool : callerTools) {
                if (!tool.enabled()) continue;
                if (namedIn(disallowedTools, tool.name())) continue;
                boolean named = namedIn(tools, tool.name());
                if (!tools.isEmpty() && !named) continue;
                // A deferred tool waits for a tool search to activate it, and
                // a project agent has none — so one the file names by hand is
                // offered outright, or it could never be reached at all.
                // Those merely inherited keep their place in the search space.
                out.add(new AgentTool(AgentToolId.random(), tool.name(), tool.description(),
                        tool.overrides(), true, tool.deferred() && !named, tool.needsApproval(),
                        tool.maxResultChars()));
            }
            return List.copyOf(out);
        }

        private static boolean namedIn(List<String> names, String tool) {
            return names.stream().anyMatch(n -> n.equalsIgnoreCase(tool));
        }
    }
}
