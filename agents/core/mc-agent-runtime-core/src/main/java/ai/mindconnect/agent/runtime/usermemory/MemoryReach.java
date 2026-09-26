package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.tool.AgentTool;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which memory an agent works on, set per tool binding under the override
 * {@code scope} — the operator's choice, never the model's:
 *
 * <ul>
 *   <li>{@link #USER} (the default): the user's own memory, shared by every
 *       agent that has the memory tools;</li>
 *   <li>{@link #AGENT}: what this agent keeps about the user, seen by no other
 *       agent — for an assistant with a job of its own, like a secretary;</li>
 *   <li>{@link #BOTH}: both; the model says with each write which one.</li>
 * </ul>
 */
public enum MemoryReach {
    USER, AGENT, BOTH;

    /** The override key on a memory tool's binding. */
    public static final String OVERRIDE = "scope";

    /** The setting as {@code ToolFactory.overridesSchema()} describes it, shared by the three tools. */
    public static Map<String, Object> overridesSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(OVERRIDE, Map.of(
                        "type", "string",
                        "enum", List.of("user", "agent", "both"),
                        "default", "user",
                        "description", "Which memory the agent works on: 'user' — the user's own, shared by "
                                + "every agent with the memory tools; 'agent' — what this agent keeps about "
                                + "the user, seen by no other agent; 'both'. Give all memory tools of an "
                                + "agent the same scope.")));
    }

    /** The binding's setting; absent or unknown reads as {@link #USER}. */
    public static MemoryReach of(AgentTool binding) {
        Object value = binding == null || binding.overrides() == null ? null : binding.overrides().get(OVERRIDE);
        if (value == null) return USER;
        return switch (value.toString().strip().toLowerCase(Locale.ROOT)) {
            case "agent" -> AGENT;
            case "both" -> BOTH;
            default -> USER;
        };
    }

    public boolean includesShared() {
        return this != AGENT;
    }

    public boolean includesAgent() {
        return this != USER;
    }
}
