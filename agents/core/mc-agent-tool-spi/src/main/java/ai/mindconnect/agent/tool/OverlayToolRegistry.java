package ai.mindconnect.agent.tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A {@link ToolRegistry} with the operator's decisions laid over it: tools
 * switched off disappear, tools with a new description carry it.
 *
 * <p>A decorator rather than a change to {@link SpiToolRegistry}, because
 * what the classpath and the registrations offer is one question and what an
 * operator wants of it is another. The inner registry stays the answer to
 * the first; this one answers the second.
 *
 * <h2>Who wins</h2>
 * Per field (concept 22 §5):
 * <ul>
 *   <li><b>description</b> — an agent's own description wins over the
 *       operator's, because whoever builds the agent knows how the tool is
 *       used there. Only when the agent says nothing does the overlay's
 *       apply.</li>
 *   <li><b>parameter descriptions</b> — the operator's apply either way; an
 *       agent's tool description settles the tool's text, not what a
 *       parameter means.</li>
 *   <li><b>enabled</b> — the operator wins, always. Otherwise an agent
 *       definition would bring back a tool that was deliberately switched
 *       off, and the switch would be a suggestion.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * The tool names are asked for on every lookup, so the overlay is read from
 * memory and refreshed only when the repository's version moved.
 */
public final class OverlayToolRegistry implements ToolRegistry {

    private final ToolRegistry delegate;
    private final ToolRepository repository;

    /** The overlay as of one repository version, swapped as a whole. */
    private record Snapshot(long version, Map<String, ToolSettings> settings) {
    }

    private volatile Snapshot snapshot;

    public OverlayToolRegistry(ToolRegistry delegate, ToolRepository repository) {
        this.delegate = delegate;
        this.repository = repository;
    }

    @Override
    public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
        ToolSettings settings = settingsFor(AliasTool.registryName(agentTool));
        if (!settings.enabledOrDefault()) {
            // Switched off by the operator. Left out rather than failed: that
            // is how an unavailable provider behaves too, and an agent bound
            // to it keeps working minus this one capability.
            return Optional.empty();
        }
        Optional<Tool> resolved = delegate.resolve(agentTool, scope);
        if (resolved.isEmpty()) {
            return resolved;
        }
        // Per field, not per record. When the agent brought its own
        // description it has already won — SpiToolRegistry applied it inside
        // the call above — but that settles the tool's text and nothing else.
        ToolSettings effective = agentSpeaksForItself(agentTool)
                ? settings.withoutDescription()
                : settings;
        return resolved.map(tool -> DescribedTool.wrap(tool, effective));
    }

    private static boolean agentSpeaksForItself(AgentTool agentTool) {
        return agentTool != null && agentTool.description() != null
                && !agentTool.description().isBlank();
    }

    @Override
    public Set<String> knownToolNames() {
        return filtered(delegate.knownToolNames());
    }

    @Override
    public Map<String, Set<String>> toolNamesByGroup() {
        Map<String, Set<String>> byGroup = new TreeMap<>();
        delegate.toolNamesByGroup().forEach((group, names) -> {
            Set<String> kept = new TreeSet<>(filtered(names));
            if (!kept.isEmpty()) {
                byGroup.put(group, kept);
            }
        });
        return byGroup;
    }

    @Override
    public Map<String, Object> overridesSchema(String toolName) {
        return delegate.overridesSchema(toolName);
    }

    @Override
    public String subgroupOf(String toolName) {
        return delegate.subgroupOf(toolName);
    }

    /**
     * The registry beneath the decisions: what the classpath and the
     * registrations offer, before anything was switched off or reworded.
     *
     * <p>Two callers need it. The warm-up starter, because binding providers
     * belongs to the registry underneath and the bean it is handed may be
     * this decorator. And the admin UI, which shows an override next to the
     * text it replaces and has to keep a row for a tool that is no longer in
     * the effective catalog because somebody switched it off. The runtime
     * itself never asks: it wants the effective answer, and that is this
     * object.
     */
    public ToolRegistry source() {
        return delegate;
    }

    /** The settings an operator recorded for one tool; never null. */
    public ToolSettings settingsFor(String toolName) {
        return overlay().getOrDefault(toolName, ToolSettings.none());
    }

    private Set<String> filtered(Set<String> names) {
        Map<String, ToolSettings> settings = overlay();
        if (settings.isEmpty()) {
            return names;
        }
        Set<String> kept = new LinkedHashSet<>(names.size());
        for (String name : names) {
            if (settings.getOrDefault(name, ToolSettings.none()).enabledOrDefault()) {
                kept.add(name);
            }
        }
        return kept;
    }

    /** The overlay, re-read when the repository moved. */
    private Map<String, ToolSettings> overlay() {
        long current = repository.version();
        Snapshot seen = snapshot;
        if (seen != null && seen.version() == current) {
            return seen.settings();
        }
        synchronized (this) {
            seen = snapshot;
            if (seen != null && seen.version() == current) {
                return seen.settings();
            }
            Snapshot loaded = new Snapshot(current, new LinkedHashMap<>(repository.all()));
            snapshot = loaded;
            return loaded.settings();
        }
    }
}
