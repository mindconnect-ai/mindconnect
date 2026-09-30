package ai.mindconnect.agent.tools.workflow.step;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a run may reach when the workflow comes from somewhere nobody vetted —
 * a project's {@code .mindconnect/workflows}: the tools and agents of the
 * agent that started it, and nothing else. Put on the run as a context
 * attribute; the tool-call, agent-call and sandbox-code steps check it before
 * they act. A run without it is a store workflow and keeps the whole registry.
 *
 * <p>A tool that needs an approval in the caller's binding is refused: a
 * step has nobody to ask, and running it anyway would take the approval
 * away.
 *
 * @param tools         the caller's tools, by name
 * @param approvalTools those of them that ask for an approval first
 * @param agents        the agents the caller may call, by name
 */
public record CallerLimits(Set<String> tools, Set<String> approvalTools, Set<String> agents) {

    public CallerLimits {
        tools = lower(tools);
        approvalTools = lower(approvalTools);
        agents = lower(agents);
    }

    /** Nothing at all: the steps that only compute still run. */
    public static CallerLimits none() {
        return new CallerLimits(Set.of(), Set.of(), Set.of());
    }

    /** Throws unless the caller could have called {@code tool} itself, without an approval. */
    public void checkTool(String step, String tool) {
        String name = key(tool);
        if (!tools.contains(name)) {
            throw new IllegalStateException("step '" + step + "': tool '" + tool
                    + "' is not one of the calling agent's tools, and a project workflow can only use those");
        }
        if (approvalTools.contains(name)) {
            throw new IllegalStateException("step '" + step + "': tool '" + tool
                    + "' asks for an approval in the calling agent's binding, which a workflow step cannot give");
        }
    }

    /** Throws unless the caller may call {@code agent}. */
    public void checkAgent(String step, String agent) {
        if (!agents.contains(key(agent))) {
            throw new IllegalStateException("step '" + step + "': agent '" + agent
                    + "' is not one the calling agent may call, and a project workflow can only call those");
        }
    }

    private static Set<String> lower(Collection<String> names) {
        return names == null ? Set.of()
                : names.stream().filter(n -> n != null && !n.isBlank()).map(CallerLimits::key)
                        .collect(Collectors.toUnmodifiableSet());
    }

    private static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }
}
