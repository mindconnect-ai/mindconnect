package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.llm.domain.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * The two delegation tools the runtime handles INLINE — they have no
 * {@code ToolFactory} and no registry entry: {@code run_agent} spawns one
 * sub-agent turn, {@code run_agents} a parallel batch, both as child tasks on
 * the queue (concept 16). Their definitions live here so the admin UIs can
 * show every available tool with its schema.
 */
public final class InlineAgentTools {

    public static final String RUN_AGENT = "run_agent";
    public static final String RUN_AGENTS = "run_agents";

    /**
     * What the tool is for, without naming agents. Which agents exist is a
     * question of the moment — the caller's roster, and whatever the project
     * in the working directory brings with it — so the names are appended
     * per turn by {@link #runAgentDefinition(List)} rather than baked in
     * here, where they were wrong for every agent whose roster differed.
     */
    private static final String BASE_DESCRIPTION =
            "Delegates a task to a specialist agent and returns its answer. Each starts with no memory "
            + "of this chat, so put everything it needs in the message. NOT for reading a file attached "
            + "to this chat (vector_search) or a file you already have a path for (file_read / document "
            + "tools).";

    private static final ToolDefinition RUN_AGENT_DEF = ToolDefinition.of(
            RUN_AGENT,
            BASE_DESCRIPTION + " Call list_agents to see which agents you can reach.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of(
                                    "type", "string",
                                    "description", "The name of the agent to call, e.g. \"web-researcher\" or \"file-finder\""
                            ),
                            "message", Map.of(
                                    "type", "string",
                                    "description", "The task or question to send to the agent"
                            )
                    ),
                    "required", new String[]{"name", "message"}
            ));

    private static final ToolDefinition RUN_AGENTS_DEF = ToolDefinition.of(
            RUN_AGENTS,
            "Delegates several INDEPENDENT tasks to agents in parallel and returns all their results together. "
                    + "Each task is {\"name\": \"<agent>\", \"message\": \"<self-contained task>\"}. "
                    + "Use this instead of calling run_agent many times when the sub-tasks do not depend on each "
                    + "other — they execute concurrently, so it is much faster. For a single task, or when one task "
                    + "needs another's output, use run_agent.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "tasks", Map.of(
                                    "type", "array",
                                    "description", "The independent tasks to run in parallel. Each runs as a fresh sub-agent.",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "name", Map.of(
                                                            "type", "string",
                                                            "description", "Name of the agent to call, e.g. \"explorer\" or \"web-researcher\""
                                                    ),
                                                    "message", Map.of(
                                                            "type", "string",
                                                            "description", "The complete, self-contained task for this agent"
                                                    )
                                            ),
                                            "required", new String[]{"name", "message"}
                                    )
                            )
                    ),
                    "required", new String[]{"tasks"}
            ));

    private InlineAgentTools() {
    }

    public static ToolDefinition runAgentDefinition() {
        return RUN_AGENT_DEF;
    }

    /**
     * The same tool, telling the model which agents it can actually reach. A
     * model reads a tool's description far more reliably than it calls
     * list_agents, so the names belong here.
     *
     * <p>Whether the sentence may claim to be complete depends on the
     * roster. A roster names every agent its holder may call, so with one the
     * list is exhaustive and says so. Without one the agent may call the
     * whole registry, which cannot be enumerated here — then the project's
     * agents are named as an addition and the rest is left to list_agents,
     * because a list that reads as complete while hiding the registry is
     * worse than no list at all.
     *
     * @param projectAgents agents the working directory's project defines
     * @param roster        the caller's {@code callableAgents}, empty when unrestricted
     */
    public static ToolDefinition runAgentDefinition(List<String> projectAgents, List<String> roster) {
        List<String> project = projectAgents == null ? List.of() : projectAgents;
        List<String> allowed = roster == null ? List.of() : roster;
        if (project.isEmpty() && allowed.isEmpty()) return RUN_AGENT_DEF;

        String names;
        if (allowed.isEmpty()) {
            names = " This project defines: " + String.join(", ", project)
                    + ". Call list_agents for the ones registered on the server.";
        } else {
            List<String> all = new java.util.ArrayList<>(project);
            for (String name : allowed) {
                if (all.stream().noneMatch(n -> n.equalsIgnoreCase(name))) all.add(name);
            }
            names = " Available to you: " + String.join(", ", all) + ".";
        }
        return ToolDefinition.of(RUN_AGENT, BASE_DESCRIPTION + names, RUN_AGENT_DEF.parametersSchema());
    }

    public static ToolDefinition runAgentsDefinition() {
        return RUN_AGENTS_DEF;
    }

    public static List<ToolDefinition> definitions() {
        return List.of(RUN_AGENT_DEF, RUN_AGENTS_DEF);
    }
}
