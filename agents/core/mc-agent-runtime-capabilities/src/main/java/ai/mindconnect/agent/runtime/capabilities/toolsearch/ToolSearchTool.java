package ai.mindconnect.agent.runtime.capabilities.toolsearch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;

/**
 * Lets an agent find the tools its definition holds back. What it can find
 * is exactly those: a tool nobody bound to the agent cannot be discovered,
 * so what an agent may do stays readable in its definition.
 */
final class ToolSearchTool implements Tool {

    static final String NAME = "tool_search";
    private static final String QUERY = "query";
    private static final int MAX_RESULTS = 5;
    static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Searches this agent's extended toolbox by name or description. Matching tools become "
                    + "available to you as regular tools from your NEXT step on: search first, then call "
                    + "the tool you found. Use it whenever the task needs a capability you do not have.",
            Map.of("type", "object",
                    "properties", Map.of(QUERY, Map.of("type", "string",
                            "description", "What you are looking for, e.g. 'read pdf', 'run python'.")),
                    "required", List.of(QUERY)));

    private final Supplier<TurnService> turns;
    private final ToolResolver tools;
    private final ToolActivation activation;

    ToolSearchTool(Supplier<TurnService> turns, ToolResolver tools, ToolActivation activation) {
        this.turns = turns;
        this.tools = tools;
        this.activation = activation;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        if (!(call.arguments().get(QUERY) instanceof String query) || query.isBlank()) {
            return ToolOutput.failed("Error: " + NAME + " needs a query");
        }
        // A tool is not told which agent runs it; the turn knows.
        List<String> deferred = turns.get().find(call.turnId())
                .map(Turn::agent)
                .map(AgentDefinition::tools)
                .orElse(List.of()).stream()
                .filter(ToolBinding::deferred)
                .map(ToolBinding::name)
                .toList();
        List<String> words = List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
        List<ToolDefinition> found = tools.tools(session).stream()
                .filter(tool -> deferred.contains(tool.name()))
                .filter(tool -> matches(tool, words))
                .limit(MAX_RESULTS)
                .toList();
        if (found.isEmpty()) {
            return ToolOutput.ok("No tool matches '" + query + "'. Searchable: " + String.join(", ", deferred));
        }
        activation.activate(session.id(), found.stream().map(ToolDefinition::name).toList());
        StringBuilder text = new StringBuilder("Available from your next step on:\n");
        found.forEach(tool -> text.append("- ").append(tool.name()).append(": ")
                .append(tool.description()).append('\n'));
        return ToolOutput.ok(text.toString());
    }

    private static boolean matches(ToolDefinition tool, List<String> words) {
        String text = (tool.name() + " " + tool.description()).toLowerCase(Locale.ROOT);
        return words.stream().anyMatch(text::contains);
    }
}
