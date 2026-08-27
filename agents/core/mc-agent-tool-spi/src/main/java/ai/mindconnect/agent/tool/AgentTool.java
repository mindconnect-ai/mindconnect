package ai.mindconnect.agent.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.UUID;

/**
 * Reference from an {@link AgentDefinition} to a tool the agent is allowed to use.
 *
 * <p>An {@code AgentTool} is intentionally a thin pointer: it carries only the
 * tool's global name plus optional agent-specific overrides. It does <strong>not</strong>
 * encode where the tool comes from (built-in, MCP, workflow, …) — that's the job
 * of the tool registry/resolver.
 *
 * <h2>{@link #overrides()}</h2>
 * Agent-level configuration that the tool implementation may consult. Examples:
 * <ul>
 *   <li>{@code baseDir} for file-rooted built-in tools, overriding the runtime default</li>
 *   <li>{@code callTimeout}, {@code requireConfirmation} (planned, not yet read in v0)</li>
 * </ul>
 *
 * <p>The legacy fields {@code toolType}, {@code toolConfig} and {@code inputSchema}
 * have been removed. Older persisted JSON containing those keys is tolerated via
 * {@link JsonIgnoreProperties} so existing agent files keep loading.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentTool(
        UUID id,
        UUID agentDefinitionId,
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
        if (overrides == null) overrides = Map.of();
    }

    /** Pre-maxResultChars constructor: no per-tool result cap. */
    public AgentTool(UUID id, UUID agentDefinitionId, String name, String description,
                     Map<String, Object> overrides, boolean enabled, boolean deferred,
                     boolean needsApproval) {
        this(id, agentDefinitionId, name, description, overrides, enabled, deferred,
                needsApproval, null);
    }

    /** Pre-needsApproval constructor: the tool runs unasked. */
    public AgentTool(UUID id, UUID agentDefinitionId, String name, String description,
                     Map<String, Object> overrides, boolean enabled, boolean deferred) {
        this(id, agentDefinitionId, name, description, overrides, enabled, deferred, false, null);
    }

    /** Pre-deferred-flag constructor: tool is always offered (deferred = false). */
    public AgentTool(UUID id, UUID agentDefinitionId, String name, String description,
                     Map<String, Object> overrides, boolean enabled) {
        this(id, agentDefinitionId, name, description, overrides, enabled, false, false, null);
    }

    /** Minimal reference: just a tool name, no overrides, enabled. */
    public static AgentTool of(UUID agentDefinitionId, String name) {
        return of(agentDefinitionId, name, null, Map.of());
    }

    /** Reference with a human-readable description but no overrides. */
    public static AgentTool of(UUID agentDefinitionId, String name, String description) {
        return of(agentDefinitionId, name, description, Map.of());
    }

    /** Reference with agent-level overrides (e.g. {@code baseDir}). */
    public static AgentTool of(UUID agentDefinitionId, String name,
                               String description, Map<String, Object> overrides) {
        return new AgentTool(UUID.randomUUID(), agentDefinitionId, name, description,
                overrides == null ? Map.of() : Map.copyOf(overrides), true);
    }
}
