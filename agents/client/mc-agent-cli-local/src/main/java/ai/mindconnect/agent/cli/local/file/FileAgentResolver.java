package ai.mindconnect.agent.cli.local.file;

import java.util.HashMap;
import java.util.List;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;

/**
 * Agents as files: {@code .mc/agents/<name>.md} in the working directory,
 * then in the home — the first that has the file wins, so a project can
 * replace an agent the user has at home.
 *
 * <pre>
 * ---
 * description: Researches one question in the project's files.
 * model: research-model
 * tools: search_files, read_file
 * agents: fact-checker
 * maxRounds: 6
 * ---
 * You research one question. …
 * </pre>
 *
 * <p>The file name is the agent's name; the body is its instructions. Read
 * on every lookup: an agent edited while the harness runs is the agent the
 * next turn gets.
 */
public final class FileAgentResolver implements AgentResolver {

    private static final int DEFAULT_MAX_ROUNDS = 10;

    private final HarnessDirectories directories;

    public FileAgentResolver(HarnessDirectories directories) {
        this.directories = directories;
    }

    @Override
    public Optional<AgentDefinition> resolve(String agentName, Session session) {
        return resolve(agentName, directories.configDirectories(session));
    }

    /** The agent of that name in the first of the {@code .mc} folders that has it. */
    public Optional<AgentDefinition> resolve(String agentName, List<Path> configDirectories) {
        return configDirectories.stream()
                .map(config -> FrontMatterDocument.read(config.resolve("agents").resolve(agentName + ".md")))
                .flatMap(Optional::stream)
                .findFirst()
                .map(document -> agent(agentName, document));
    }

    private static AgentDefinition agent(String agentName, FrontMatterDocument document) {
        // Every key of the header, known to the runtime or not: what a plugin reads from an agent file.
        Map<String, Object> metadata = new HashMap<>(document.header());
        metadata.put(SubAgentPlugin.SUB_AGENTS, document.list("agents"));
        return new AgentDefinition(
                agentName,
                document.value("description", ""),
                document.body(),
                new ModelSettings(document.header().get("model"), null, null,
                        document.header().get("reasoningEffort"), Map.of()),
                document.list("tools").stream().map(ToolBinding::of).toList(),
                Integer.parseInt(document.value("maxRounds", String.valueOf(DEFAULT_MAX_ROUNDS))),
                metadata);
    }
}
