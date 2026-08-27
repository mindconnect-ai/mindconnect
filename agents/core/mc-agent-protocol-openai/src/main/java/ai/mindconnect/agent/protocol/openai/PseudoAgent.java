package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.agent.protocol.api.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Local stand-in for an {@code AgentDefinition} when the MindConnect runtime
 * is not in play: everything the OpenAI Responses API needs per request,
 * registered under a name so the protocol's "agents are referenced by name"
 * contract keeps working.
 *
 * <p>Two kinds of tools, deliberately separate:
 * <ul>
 *   <li>{@code tools} — function tools. Without our runtime there is nobody
 *       server-side to execute them: every one is effectively a client tool.
 *       A response ending on an open function call surfaces as
 *       {@code INCOMPLETE(WAITING_FOR_TOOL_OUTPUT)} — answer it manually or
 *       wrap the backend in a {@code ToolLoop}.</li>
 *   <li>{@code hostedTools} — OpenAI's built-in tools (web_search,
 *       file_search, code_interpreter, image_generation, …), passed through
 *       verbatim. They execute INSIDE OpenAI and appear as already-closed
 *       call items in the output; they can never look "open".</li>
 *   <li>{@code agentTools} — names of other registered pseudo agents this
 *       agent may delegate to. The backend injects a {@code run_agent}
 *       function tool and runs the delegation itself (agent-as-tool, the
 *       same pattern OpenAI's Agents SDK uses client-side): each delegation
 *       spawns a real child response, and the parent's output shows an
 *       {@code AgentCall} item with the {@code childResponseId}.</li>
 * </ul>
 */
public record PseudoAgent(
        String name,
        String model,
        String instructions,
        List<ToolDefinition> tools,
        List<Map<String, Object>> hostedTools,
        Object toolChoice,
        List<String> agentTools
) {

    public static PseudoAgent of(String name, String model, String instructions) {
        return new PseudoAgent(name, model, instructions, List.of(), List.of(), null, List.of());
    }

    public PseudoAgent withTools(List<ToolDefinition> tools) {
        return new PseudoAgent(name, model, instructions, tools, hostedTools, toolChoice, agentTools);
    }

    /** Declares which other registered pseudo agents this agent may delegate to. */
    public PseudoAgent withAgentTools(String... names) {
        return new PseudoAgent(name, model, instructions, tools, hostedTools, toolChoice, List.of(names));
    }

    /** Adds a hosted tool by its OpenAI type, e.g. {@code "web_search"}. */
    public PseudoAgent withHostedTool(String type) {
        return withHostedTool(Map.of("type", type));
    }

    /** Adds a hosted tool with full config, e.g. file_search with vector_store_ids. */
    public PseudoAgent withHostedTool(Map<String, Object> config) {
        List<Map<String, Object>> next = new ArrayList<>(hostedTools);
        next.add(config);
        return new PseudoAgent(name, model, instructions, tools, List.copyOf(next), toolChoice, agentTools);
    }

    /**
     * OpenAI {@code tool_choice}: {@code "required"} forces the model to use
     * a tool instead of e.g. answering with a code listing as plain text.
     * Also accepts the object forms ({@code {"type": "function", "name": …}}).
     */
    public PseudoAgent withToolChoice(Object toolChoice) {
        return new PseudoAgent(name, model, instructions, tools, hostedTools, toolChoice, agentTools);
    }
}
