package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
import java.util.Map;

/**
 * One tool an agent may use, and how this agent uses it.
 *
 * @param deferred whether the tool is held back until the agent searches for
 *                 it. An agent with many tools would otherwise carry every
 *                 definition in every model call; what it can find stays
 *                 exactly what its definition names
 * @param metadata what this agent sets for the tool and the runtime does not
 *                 read: parameters fixed in advance, a description of its
 *                 own, whether a call has to be approved. Whoever resolves
 *                 the tool or rules on it reads its own keys
 */
public record ToolBinding(String name, boolean deferred, Map<String, Object> metadata) {

    public ToolBinding {
        metadata = Map.copyOf(metadata);
    }

    public static ToolBinding of(String name) {
        return new ToolBinding(name, false, Map.of());
    }

    public static ToolBinding deferred(String name) {
        return new ToolBinding(name, true, Map.of());
    }

    public static List<ToolBinding> allOf(String... names) {
        return List.of(names).stream().map(ToolBinding::of).toList();
    }
}
