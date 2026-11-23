package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;

/**
 * One tool an agent may use.
 *
 * @param deferred whether the tool is held back until the agent searches for
 *                 it. An agent with many tools would otherwise carry every
 *                 definition in every model call; what it can find stays
 *                 exactly what its definition names
 */
public record ToolBinding(String name, boolean deferred) {

    public static ToolBinding of(String name) {
        return new ToolBinding(name, false);
    }

    public static ToolBinding deferred(String name) {
        return new ToolBinding(name, true);
    }

    public static List<ToolBinding> allOf(String... names) {
        return List.of(names).stream().map(ToolBinding::of).toList();
    }
}
