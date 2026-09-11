package ai.mindconnect.agent.tool;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A {@link ToolRegistry} with the installation's own say applied: a tool the
 * deployment switches off ({@code mindconnect.tools.disabled}) does not exist
 * here. It leaves every catalog and never resolves.
 *
 * <p>It sits beneath the operator's {@link OverlayToolRegistry}, not above
 * it. What an installation does not offer, tool settings cannot switch back
 * on, and an agent definition that names it goes without — the way it goes
 * without a provider that is not on the classpath. That is the difference
 * between a decision taken in the admin UI and one taken where the server is
 * deployed: a shared server turns off {@code bash}, which nothing confines to
 * a chat's directory, and no admin can undo that from a browser.
 */
public final class ConfiguredToolRegistry implements ToolRegistry {

    private final ToolRegistry delegate;
    private final Set<String> disabled;

    private ConfiguredToolRegistry(ToolRegistry delegate, Set<String> disabled) {
        this.delegate = delegate;
        this.disabled = disabled;
    }

    /**
     * {@code delegate} without the tools {@code disabledNames} lists — tool
     * names, comma-separated, blanks ignored — or {@code delegate} itself
     * when it lists none.
     */
    public static ToolRegistry of(ToolRegistry delegate, String disabledNames) {
        Set<String> names = parse(disabledNames);
        return names.isEmpty() ? delegate : new ConfiguredToolRegistry(delegate, names);
    }

    /** The names in a comma-separated list, in order, without blanks or repeats. */
    static Set<String> parse(String disabledNames) {
        if (disabledNames == null || disabledNames.isBlank()) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (String name : disabledNames.split(",")) {
            if (!name.isBlank()) names.add(name.strip());
        }
        return Collections.unmodifiableSet(names);
    }

    /** The tools this installation does not offer. */
    public Set<String> disabled() {
        return disabled;
    }

    @Override
    public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
        // By the name the registry knows, so an agent's alias for a switched
        // off tool does not bring it back under another name.
        if (disabled.contains(AliasTool.registryName(agentTool))) {
            return Optional.empty();
        }
        return delegate.resolve(agentTool, scope);
    }

    @Override
    public Set<String> knownToolNames() {
        Set<String> kept = new LinkedHashSet<>(delegate.knownToolNames());
        kept.removeAll(disabled);
        return kept;
    }

    @Override
    public Map<String, Set<String>> toolNamesByGroup() {
        Map<String, Set<String>> byGroup = new TreeMap<>();
        delegate.toolNamesByGroup().forEach((group, names) -> {
            Set<String> kept = new TreeSet<>(names);
            kept.removeAll(disabled);
            if (!kept.isEmpty()) {
                byGroup.put(group, kept);
            }
        });
        return byGroup;
    }

    @Override
    public Map<String, Object> overridesSchema(String toolName) {
        return disabled.contains(toolName) ? Map.of() : delegate.overridesSchema(toolName);
    }

    @Override
    public String subgroupOf(String toolName) {
        return delegate.subgroupOf(toolName);
    }

    @Override
    public void releaseSession(ai.mindconnect.agent.SessionId sessionId) {
        // A tool this installation does not offer holds nothing; the rest is
        // held by the registry beneath, which has to hear about the session.
        delegate.releaseSession(sessionId);
    }

    @Override
    public ToolRegistry source() {
        return delegate;
    }
}
