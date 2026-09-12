package ai.mindconnect.agent.runtime.service.agents;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.runtime.markdown.FrontMatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        FrontMatter.Parsed parsed = FrontMatter.parse(content);
        String prompt = parsed.body().strip();
        if (prompt.isEmpty()) return null;
        String name = parsed.get("name", fileName).strip();
        if (name.isEmpty()) name = fileName;
        return new ProjectAgent(name, parsed.get("description", ""), prompt,
                parsed.list("tools"), parsed.list("disallowedtools"),
                blankToNull(parsed.fields().get("model")));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * An agent a project defines beside its code.
     *
     * @param tools the tools the file names; {@code null} when it has no
     *              {@code tools} field, which keeps the caller's own — and an
     *              empty list when it names none, which keeps nothing
     */
    public record ProjectAgent(String name,
                               String description,
                               String systemPrompt,
                               List<String> tools,
                               List<String> disallowedTools,
                               String model) {

        public ProjectAgent {
            tools = tools == null ? null : List.copyOf(tools);
            disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        }

        /**
         * The tools this agent may use: the caller's own, narrowed by the
         * file. {@code disallowedTools} is taken away first, then
         * {@code tools} keeps what it names. A file without {@code tools}
         * keeps everything the caller has; {@code tools: []} keeps nothing —
         * reading an empty list as "no restriction" would hand the widest
         * set to the file that asked for the narrowest. A name the caller
         * does not have is not an error and not a grant — it simply matches
         * nothing.
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
                boolean named = tools != null && namedIn(tools, tool.name());
                if (tools != null && !named) continue;
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
