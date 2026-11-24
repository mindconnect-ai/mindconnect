package ai.mindconnect.agent.runtime.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.subagent.ProjectAgentSource;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.skill.domain.FrontMatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A project's agents as files: {@code .mindconnect/agents/<name>.md} in the
 * session's working directory, the shape a couple of other coding tools
 * use, so a definition can be moved between them:
 *
 * <pre>
 * ---
 * name: verifier
 * description: Checks that a change really builds and its tests pass
 * tools: bash, file_read, grep
 * model: claude-haiku-default
 * ---
 * You verify. Run the build and the tests, then say what you ran.
 * </pre>
 *
 * <p>Everything but the body is optional; the name defaults to the file's.
 * Read anew on every call, so an edit holds from the next call on.
 *
 * <p><b>Tools are narrowed, never granted.</b> The agent gets the caller's
 * own bindings — approval and all — less what {@code disallowedTools} takes
 * away, and only what {@code tools} names when the file has that field. It
 * calls whom the caller may call. Opening someone else's repository
 * therefore cannot hand it a shell the caller was not given.
 */
final class ProjectAgentFiles implements ProjectAgentSource {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentFiles.class);

    private static final String DIRECTORY = ".mindconnect/agents";
    private static final String SUFFIX = ".md";

    /** A definition longer than this is a document, not an agent. */
    private static final int MAX_BYTES = 100_000;

    @Override
    public List<AgentDefinition> agents(AgentDefinition caller, Session session) {
        if (!(session.metadata().get(ManagedSession.WORKING_DIRECTORY) instanceof String working)
                || working.isBlank()) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(Path.of(working).resolve(DIRECTORY))) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SUFFIX))
                    .sorted()
                    .map(file -> read(file, caller))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException | InvalidPathException e) {
            // No such directory is the usual case: most projects bring no agents.
            return List.of();
        }
    }

    /** The agent in this file; empty for one that cannot be read or has nothing to run on. */
    private static Optional<AgentDefinition> read(Path file, AgentDefinition caller) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                log.debug("Project agent {} is too large to read", file);
                return Optional.empty();
            }
            String fileName = file.getFileName().toString();
            return agent(fileName.substring(0, fileName.length() - SUFFIX.length()),
                    FrontMatter.parse(Files.readString(file)), caller);
        } catch (IOException | RuntimeException e) {
            log.debug("Project agent {} could not be read: {}", file, e.toString());
            return Optional.empty();
        }
    }

    static Optional<AgentDefinition> agent(String fileName, FrontMatter.Parsed file, AgentDefinition caller) {
        String instructions = file.body().strip();
        if (instructions.isEmpty()) {
            return Optional.empty();
        }
        String name = file.get("name", fileName).isBlank() ? fileName : file.get("name", fileName).strip();
        String model = file.get("model", "").strip();
        ModelSettings callers = caller.modelSettings();
        Map<String, Object> metadata = new HashMap<>();
        // Whose tools these are: a binding is resolved, and ruled on, as the caller's.
        if (caller.metadata().containsKey(ManagedAgent.AGENT_ID)) {
            metadata.put(ManagedAgent.AGENT_ID, caller.metadata().get(ManagedAgent.AGENT_ID));
        }
        // An agent limited to two others cannot reach a third by way of a file in the repository.
        metadata.put(SubAgentPlugin.SUB_AGENTS, SubAgentPlugin.subAgentsOf(caller));
        return Optional.of(new AgentDefinition(name, file.get("description", ""), instructions,
                model.isEmpty() ? callers : new ModelSettings(model, callers.temperature(),
                        callers.maxOutputTokens(), callers.reasoningEffort(), callers.extra()),
                narrowed(caller.tools(), file.list("tools"), file.list("disallowedtools")),
                caller.maxRounds(), metadata));
    }

    /**
     * @param kept the tools the file names; {@code null} for a file without
     *             the field, which keeps the caller's own — an empty list
     *             keeps none: reading it as "no restriction" would hand the
     *             widest set to the file that asked for the narrowest
     */
    private static List<ToolBinding> narrowed(List<ToolBinding> callers, List<String> kept, List<String> disallowed) {
        List<ToolBinding> tools = new ArrayList<>();
        for (ToolBinding tool : callers) {
            boolean named = kept != null && namedIn(kept, tool.name());
            if ((disallowed != null && namedIn(disallowed, tool.name())) || (kept != null && !named)) {
                continue;
            }
            // A deferred tool waits for a tool search; one the file names by hand is offered outright.
            tools.add(new ToolBinding(tool.name(), tool.deferred() && !named, tool.metadata()));
        }
        return tools;
    }

    private static boolean namedIn(List<String> names, String tool) {
        return names.stream().anyMatch(name -> name.equalsIgnoreCase(tool));
    }
}
