package ai.mindconnect.adminui.setup;

import ai.mindconnect.adminui.ui.component.ToolCatalogComponent;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The things a picker offers when tools come in sets: a whole group, a
 * subgroup within it, every group on one connection, or a single tool.
 *
 * <p>Most tools belong together — the five email tools, the four SharePoint
 * ones, the thirteen a Microsoft account brings — and nobody wants to add
 * them one at a time. A bundle is picked whole and <em>expands</em> into its
 * tools right there: what lands on the agent or in the user's account is the
 * plain list, and taking one tool out again is the existing Remove. That
 * keeps the stored shape as it is; the price is that a tool added to a group
 * later does not appear on its own, which is also the honest behaviour for a
 * list somebody has edited.
 *
 * <p>Keys are stable and self-describing: {@code group:email},
 * {@code group:mcp/github}, {@code provider:microsoft}, {@code tool:ftp_read_file}.
 */
public final class ToolBundles {

    /** One thing to pick. {@code tools} is what it expands to, in catalogue order. */
    public record Bundle(String key, String label, List<String> tools) {
        public Bundle {
            tools = List.copyOf(tools);
        }
        public boolean single() { return key.startsWith("tool:"); }
    }

    private final List<Bundle> bundles;

    private ToolBundles(List<Bundle> bundles) {
        this.bundles = List.copyOf(bundles);
    }

    /**
     * What {@code registry} offers, minus the names {@code offered} rejects
     * (the derived tools of an agent, say). Sets first, then single tools.
     */
    public static ToolBundles of(ToolRegistry registry, Predicate<String> offered) {
        Objects.requireNonNull(registry, "registry");
        Map<String, Set<String>> byGroup = new TreeMap<>();
        registry.toolNamesByGroup().forEach((group, names) -> names.stream().filter(offered).forEach(name ->
                byGroup.computeIfAbsent(group == null ? "general" : group, g -> new LinkedHashSet<>()).add(name)));

        List<Bundle> sets = new ArrayList<>();
        Map<String, List<String>> byProvider = new LinkedHashMap<>();
        Map<String, ConnectionSpec> specs = new LinkedHashMap<>();
        Map<String, Set<String>> groupsOfProvider = new LinkedHashMap<>();

        byGroup.forEach((group, names) -> {
            List<String> all = new ArrayList<>(names);
            sets.add(new Bundle("group:" + group, ToolCatalogComponent.displayGroup(group) + count(all.size()), all));
            // A subgroup is a source within the group — one MCP server among
            // several. Offered only where the group actually splits.
            Map<String, List<String>> bySubgroup = new LinkedHashMap<>();
            for (String name : all) {
                String sub = registry.subgroupOf(name);
                if (sub != null) bySubgroup.computeIfAbsent(sub, s -> new ArrayList<>()).add(name);
                registry.connectionSpecOf(name).ifPresent(spec -> {
                    specs.putIfAbsent(spec.provider(), spec);
                    byProvider.computeIfAbsent(spec.provider(), p -> new ArrayList<>()).add(name);
                    groupsOfProvider.computeIfAbsent(spec.provider(), p -> new LinkedHashSet<>()).add(group);
                });
            }
            if (bySubgroup.size() > 1 || (bySubgroup.size() == 1 && bySubgroup.values().iterator().next().size() < all.size())) {
                bySubgroup.forEach((sub, subNames) -> sets.add(new Bundle("group:" + group + "/" + sub,
                        ToolCatalogComponent.displayGroup(group) + " / " + sub + count(subNames.size()), subNames)));
            }
        });
        // Everything one account brings, when that is more than one group —
        // "the Microsoft tools" is how people think of them.
        byProvider.forEach((provider, names) -> {
            if (groupsOfProvider.get(provider).size() > 1) {
                sets.add(new Bundle("provider:" + provider,
                        specs.get(provider).title() + " — everything on it" + count(names.size()), names));
            }
        });

        List<Bundle> singles = new ArrayList<>();
        byGroup.forEach((group, names) -> names.forEach(name ->
                singles.add(new Bundle("tool:" + name, ToolCatalogComponent.displayGroup(group) + " · " + name, List.of(name)))));

        List<Bundle> all = new ArrayList<>(sets);
        all.addAll(singles);
        return new ToolBundles(all);
    }

    /** Nothing to pick — a host without a registry. */
    public static ToolBundles none() {
        return new ToolBundles(List.of());
    }

    public List<Bundle> all() {
        return bundles;
    }

    public boolean isEmpty() {
        return bundles.isEmpty();
    }

    /**
     * The tools behind the picked keys, each once, in the order picked. A key
     * nobody offers is ignored rather than stored — a hand-typed value must
     * not produce a tool that never resolves.
     */
    public List<String> expand(List<String> keys) {
        Set<String> names = new LinkedHashSet<>();
        if (keys == null) return List.of();
        for (String key : keys) {
            bundles.stream().filter(b -> b.key().equals(key)).findFirst()
                    .ifPresent(b -> names.addAll(b.tools()));
        }
        return List.copyOf(names);
    }

    private static String count(int n) {
        return "  (" + n + (n == 1 ? " tool)" : " tools)");
    }
}
