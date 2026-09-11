package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.EntityId;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Reference from an {@code AgentDefinition} to a tool the agent is allowed to use.
 *
 * <p>An {@code AgentTool} is intentionally a thin pointer: it carries only the
 * tool's global name plus optional agent-specific overrides. It does <strong>not</strong>
 * encode where the tool comes from (built-in, MCP, workflow, …) — that's the job
 * of the tool registry/resolver. Nor does it point back at its agent: the
 * definition that lists the binding knows which agent it is, and a tool call
 * learns it from its {@link ToolCallScope}.
 *
 * <h2>{@link #overrides()}</h2>
 * Agent-level configuration that the tool implementation may consult. Examples:
 * <ul>
 *   <li>{@code baseDir} for file-rooted built-in tools, overriding the runtime default</li>
 *   <li>{@code callTimeout}, {@code requireConfirmation} (planned, not yet read in v0)</li>
 * </ul>
 *
 * <p>The legacy fields {@code toolType}, {@code toolConfig}, {@code inputSchema}
 * and {@code agentDefinitionId} have been removed. Older persisted JSON containing
 * those keys is tolerated via {@link JsonIgnoreProperties} so existing agent files
 * keep loading.
 */
public record AgentTool(
        /** The binding's own id; unique within its agent. */
        AgentToolId id,
        String name,
        String description,
        Map<String, Object> overrides,
        boolean enabled,
        /**
         * Deferred tools are not offered to the LLM up front: they are the
         * agent's {@code tool_search} space and join the context only once a
         * search activates them. Keeps large tool sets (MCP bundles) out of
         * the prompt until needed. Default {@code false} — the tool is always
         * offered, as before this flag existed.
         */
        boolean deferred,
        /**
         * A human must approve every call of this tool before it runs — the
         * turn ends {@code INCOMPLETE(WAITING_FOR_APPROVAL)} and the answer
         * arrives as input of the next turn (concept 16). The approval
         * memory is per tool NAME and per session ("allow for this
         * session"), never per parameter set — the request always shows the
         * concrete arguments, only the memory is coarse. Default
         * {@code false}: the tool runs unasked, as before this flag existed.
         */
        boolean needsApproval,
        /**
         * Hard cap on this tool's result, in characters — the output is CUT
         * at persist time (with a visible truncation note), so oversized
         * dumps never reach the conversation, the window or the DB. This is
         * real loss, opt-in per tool; {@code null} (the default) means no
         * per-tool cap. A runtime-wide safety cap applies regardless.
         */
        Integer maxResultChars
) {
    public AgentTool {
        if (id == null) throw new IllegalArgumentException("An agent tool needs an id");
        if (overrides == null) overrides = Map.of();
    }

    /** A binding with the default flags: enabled, offered up front, unasked, uncapped. */
    public AgentTool(AgentToolId id, String name, String description, Map<String, Object> overrides) {
        this(id, name, description, overrides, true, false, false, null);
    }

    /** Minimal reference: just a tool name, no overrides, enabled. */
    public static AgentTool of(String name) {
        return of(name, null, Map.of());
    }

    /** Reference with a human-readable description but no overrides. */
    public static AgentTool of(String name, String description) {
        return of(name, description, Map.of());
    }

    /** Reference with agent-level overrides (e.g. {@code baseDir}). */
    public static AgentTool of(String name,
                               String description, Map<String, Object> overrides) {
        return new AgentTool(AgentToolId.random(), name, description,
                overrides == null ? Map.of() : Map.copyOf(overrides));
    }

    /**
     * The persisted shape of a binding, lenient where a hand-written file is:
     * a missing id gets a fresh one, missing flags take their defaults. Older
     * files carry an {@code agentDefinitionId}; it is ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Json(String id, String name, String description, Map<String, Object> overrides,
                       Boolean enabled, Boolean deferred, Boolean needsApproval, Integer maxResultChars) {

        public AgentTool toTool() {
            return new AgentTool(
                    AgentToolId.of(id == null ? EntityId.randomValue() : id),
                    name, description, overrides,
                    enabled == null || enabled,
                    deferred != null && deferred,
                    needsApproval != null && needsApproval,
                    maxResultChars);
        }
    }

    public Json toJson() {
        return new Json(id.value(), name, description, overrides, enabled, deferred, needsApproval, maxResultChars);
    }

    public static List<AgentTool> fromJson(List<Json> tools) {
        return tools == null ? List.of() : tools.stream().map(t -> t.toTool()).toList();
    }

    public static List<Json> toJson(List<AgentTool> tools) {
        return tools == null ? List.of() : tools.stream().map(AgentTool::toJson).toList();
    }
}
