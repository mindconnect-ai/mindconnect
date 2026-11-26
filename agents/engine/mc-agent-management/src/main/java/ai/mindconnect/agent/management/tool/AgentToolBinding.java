package ai.mindconnect.agent.management.tool;

import java.util.HashMap;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;

/**
 * How an agent's definition binds a tool of the tool registry: the binding
 * names the tool, and its metadata carries what this agent set for it. The
 * one place that knows the keys, for whoever writes a binding — an agent's
 * tool page, an imported agent file — and for the resolver that reads it.
 */
public final class AgentToolBinding {

    /** Metadata of a binding: the entry's id among the agent's tools. */
    public static final String TOOL_ID = "agentToolId";
    /** Metadata of a binding: the parameters this agent fixed in advance. */
    public static final String OVERRIDES = "overrides";
    /** Metadata of a binding: the description this agent gives the tool. */
    public static final String DESCRIPTION = "description";
    /** Metadata of a binding: how long a result may be for this agent. */
    public static final String MAX_RESULT_CHARS = "maxResultChars";

    private AgentToolBinding() {
    }

    /** How an agent's definition binds a tool, from what the agent set for it. */
    public static ToolBinding of(AgentTool tool) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(TOOL_ID, tool.id().value());
        metadata.put(OVERRIDES, tool.overrides());
        if (tool.description() != null) {
            metadata.put(DESCRIPTION, tool.description());
        }
        if (tool.maxResultChars() != null) {
            metadata.put(MAX_RESULT_CHARS, tool.maxResultChars());
        }
        if (tool.needsApproval()) {
            metadata.put(PermissionPlugin.NEEDS_APPROVAL, true);
        }
        return new ToolBinding(tool.name(), tool.deferred(), metadata);
    }

    /** A bound tool as the tool registry resolves it and as an agent's tool page edits it. */
    @SuppressWarnings("unchecked")
    public static AgentTool agentTool(ToolBinding binding) {
        Map<String, Object> metadata = binding.metadata();
        return new AgentTool(
                metadata.get(TOOL_ID) instanceof String id ? AgentToolId.of(id) : AgentToolId.random(),
                binding.name(),
                (String) metadata.get(DESCRIPTION),
                metadata.get(OVERRIDES) instanceof Map<?, ?> overrides ? (Map<String, Object>) overrides : Map.of(),
                true,
                binding.deferred(),
                Boolean.TRUE.equals(metadata.get(PermissionPlugin.NEEDS_APPROVAL)),
                metadata.get(MAX_RESULT_CHARS) instanceof Number limit ? limit.intValue() : null);
    }
}
