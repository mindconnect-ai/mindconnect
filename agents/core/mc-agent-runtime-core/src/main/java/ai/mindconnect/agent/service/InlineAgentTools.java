package ai.mindconnect.agent.service;

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

    private static final ToolDefinition RUN_AGENT_DEF = ToolDefinition.of(
            RUN_AGENT,
            "Delegates a task to a specialist agent and returns its answer. Use it for: web-researcher "
            + "(anything on the live web — current facts, a URL to read, 'the latest'), file-finder "
            + "(locating the user's own files when you have no path), explorer (mapping a codebase or "
            + "directory tree). Each starts with no memory of this chat, so put everything it needs in the "
            + "message. NOT for reading a file attached to this chat (vector_search) or a file you already "
            + "have a path for (file_read / document tools).",
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

    public static ToolDefinition runAgentsDefinition() {
        return RUN_AGENTS_DEF;
    }

    public static List<ToolDefinition> definitions() {
        return List.of(RUN_AGENT_DEF, RUN_AGENTS_DEF);
    }
}
