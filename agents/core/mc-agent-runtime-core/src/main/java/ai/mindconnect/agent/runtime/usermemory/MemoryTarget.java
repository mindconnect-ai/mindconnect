package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which memory one tool call works on: the binding's {@link MemoryReach}
 * decides, and under {@link MemoryReach#BOTH} the model's {@code scope}
 * argument. Shared by the three memory tools, so they read the argument
 * and word their answers the same way.
 */
final class MemoryTarget {

    /** The model's argument under {@link MemoryReach#BOTH}. */
    static final String ARG = "scope";

    private MemoryTarget() {}

    /** The {@code scope} parameter a tool offers, when the binding lets the model choose. */
    static void addParameter(Map<String, Object> properties, MemoryReach reach, String description) {
        if (reach == MemoryReach.BOTH) {
            properties.put(ARG, Map.of("type", "string", "enum", List.of("user", "agent"),
                    "description", description));
        }
    }

    /**
     * The memories the call may address, in the order to try them: one,
     * except under {@link MemoryReach#BOTH} without an argument — then the
     * agent's own before the shared one. A {@code null} element is the
     * user's own memory.
     */
    static List<AgentId> candidates(MemoryReach reach, AgentId agentId, Object argument) {
        List<AgentId> out = new ArrayList<>();
        switch (reach) {
            case USER -> out.add(null);
            case AGENT -> out.add(requireAgent(agentId));
            case BOTH -> {
                String chosen = argument == null ? "" : argument.toString().strip().toLowerCase(java.util.Locale.ROOT);
                switch (chosen) {
                    case "user" -> out.add(null);
                    case "agent" -> out.add(requireAgent(agentId));
                    case "" -> {
                        if (agentId != null) out.add(agentId);
                        out.add(null);
                    }
                    default -> throw new IllegalArgumentException("unknown scope '" + argument + "' — expected user|agent");
                }
            }
        }
        return out;
    }

    /** The one memory a write goes to; under {@link MemoryReach#BOTH} the argument is required. */
    static AgentId forWrite(MemoryReach reach, AgentId agentId, Object argument) {
        if (reach == MemoryReach.BOTH && (argument == null || argument.toString().isBlank())) {
            throw new IllegalArgumentException("'scope' is required: 'user' for what every agent should know "
                    + "about the user, 'agent' for what only you need");
        }
        return candidates(reach, agentId, argument).get(0);
    }

    /** How an answer names the memory. */
    static String label(AgentId agentId) {
        return agentId == null ? "the user's memory" : "your own memory";
    }

    private static AgentId requireAgent(AgentId agentId) {
        if (agentId == null) {
            throw new IllegalArgumentException("this call runs outside an agent — there is no agent memory here");
        }
        return agentId;
    }
}
