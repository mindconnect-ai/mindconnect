package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.common.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ToolRegistry} that discovers tool sources from the classpath via
 * {@link ServiceLoader}. Two SPIs are consulted in order:
 *
 * <ol>
 *   <li>{@link ToolFactory} — strictly 1:1 with a tool name. Existing built-ins
 *       (file/web/document/etc.) register this way and are unchanged.</li>
 *   <li>{@link MultiToolProvider} — contributes multiple names from one
 *       registration. Used for MCP bundles (e.g. all Gmail sub-tools share
 *       one provider and one cached connection), for built-in bundles
 *       that want to share resources, and for dynamic sources such as
 *       persisted workflows.</li>
 * </ol>
 *
 * <p>Provider names are resolved <em>live</em>: {@link #knownToolNames()} and
 * {@link #resolve} ask each provider for its current {@code toolNames()} on
 * every call, so a provider backed by mutable data (a workflow store, a
 * remote catalog) surfaces additions and removals without a restart. Only
 * the provider <em>set</em> is fixed at construction (ServiceLoader scan).
 *
 * <p>{@code ToolFactory} wins on name collisions to keep behavior stable
 * during the gradual migration from single- to multi-tool registration;
 * between providers, the first registered provider claiming a name wins.
 *
 * <p>Adding a new tool source is purely additive: implement the SPI, list it
 * in {@code META-INF/services/...ToolFactory} or
 * {@code META-INF/services/...MultiToolProvider}. No runtime code change.
 */
public class SpiToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(SpiToolRegistry.class);

    private final Map<String, ToolFactory> factoriesByName;
    private final List<MultiToolProvider> providers;

    public SpiToolRegistry(ToolEnvironment environment) {
        this(environment, Thread.currentThread().getContextClassLoader());
    }

    public SpiToolRegistry(ToolEnvironment environment, ClassLoader classLoader) {
        // 1) Single-tool ToolFactory SPI
        Map<String, ToolFactory> facMap = new LinkedHashMap<>();
        for (ToolFactory factory : ServiceLoader.load(ToolFactory.class, classLoader)) {
            try {
                factory.bind(environment);
            } catch (RuntimeException e) {
                log.error("ToolFactory '{}' ({}) failed to bind — tool will be unavailable",
                        factory.name(), factory.getClass().getName(), e);
                continue;
            }
            if (!factory.isAvailable()) {
                log.warn("ToolFactory '{}' ({}) reports itself unavailable — tool will be skipped",
                        factory.name(), factory.getClass().getSimpleName());
                continue;
            }
            ToolFactory previous = facMap.putIfAbsent(factory.name(), factory);
            if (previous != null) {
                log.warn("Duplicate ToolFactory for name '{}': keeping {} and ignoring {}",
                        factory.name(),
                        previous.getClass().getName(),
                        factory.getClass().getName());
            }
        }
        this.factoriesByName = Map.copyOf(facMap);

        // 2) Multi-tool MultiToolProvider SPI. Only the provider set is fixed
        //    here — each provider's toolNames() is consulted live on every
        //    lookup, so dynamic sources (workflow store, remote catalogs)
        //    surface changes without a restart.
        List<MultiToolProvider> active = new ArrayList<>();
        for (MultiToolProvider provider : ServiceLoader.load(MultiToolProvider.class, classLoader)) {
            try {
                provider.bind(environment);
            } catch (RuntimeException e) {
                log.error("MultiToolProvider {} failed to bind — bundle will be unavailable",
                        provider.getClass().getName(), e);
                continue;
            }
            if (!provider.isAvailable()) {
                log.warn("MultiToolProvider {} reports itself unavailable — bundle will be skipped",
                        provider.getClass().getSimpleName());
                continue;
            }
            active.add(provider);
        }
        this.providers = List.copyOf(active);

        log.info("SpiToolRegistry: registered {} single-tool factor(ies) and {} provider(s) currently contributing {} additional tool(s)",
                factoriesByName.size(), providers.size(),
                providers.stream().mapToInt(p -> p.toolNames().size()).sum());
        if (log.isDebugEnabled()) {
            log.debug("  factories: {}", factoriesByName.keySet());
            for (MultiToolProvider provider : providers) {
                log.debug("  provider {}: {}", provider.getClass().getSimpleName(), provider.toolNames());
            }
        }
    }

    @Override
    public Set<String> knownToolNames() {
        // Union of ToolFactory names + each provider's *current* names.
        // ToolFactory keys come first (their order) followed by provider keys.
        Set<String> union = new LinkedHashSet<>(factoriesByName.keySet());
        for (MultiToolProvider provider : providers) {
            union.addAll(provider.toolNames());
        }
        return Set.copyOf(union);
    }

    @Override
    public Map<String, Set<String>> toolNamesByGroup() {
        // Sorted groups, sorted names — a stable view for catalogs and pickers.
        Map<String, Set<String>> byGroup = new java.util.TreeMap<>();
        for (ToolFactory factory : factoriesByName.values()) {
            byGroup.computeIfAbsent(groupOrDefault(factory.group()), g -> new java.util.TreeSet<>())
                    .add(factory.name());
        }
        for (MultiToolProvider provider : providers) {
            Set<String> names = provider.toolNames();
            if (names.isEmpty()) continue;
            byGroup.computeIfAbsent(groupOrDefault(provider.group()), g -> new java.util.TreeSet<>())
                    .addAll(names);
        }
        return byGroup;
    }

    private static String groupOrDefault(String group) {
        return group == null || group.isBlank() ? "general" : group;
    }

    @Override
    public Map<String, Object> overridesSchema(String toolName) {
        ToolFactory factory = factoriesByName.get(toolName);
        return factory == null ? Map.of() : factory.overridesSchema();
    }

    @Override
    public Optional<Tool> resolve(AgentTool agentTool, Namespace namespace, String userId, UUID sessionId) {
        if ("run_agent".equals(agentTool.name())) {
            return Optional.empty(); // handled inline by AgentChatService.dispatchSubAgent
        }
        ToolCallScope scope = new ToolCallScope(namespace, userId, sessionId, agentTool.agentDefinitionId());

        // The alias override may point this agent tool at a different registry
        // name (exposed to the LLM under the agent tool's own name).
        String registryName = AliasTool.registryName(agentTool);

        ToolFactory factory = factoriesByName.get(registryName);
        if (factory != null) {
            // Alias innermost (so pins reference the real parameter names),
            // then the required-parameter guard, then pinning outermost: a
            // pinned value must satisfy the requirement it would otherwise
            // trip, and pinning is what removes the name from the schema
            // again. The single funnel every tool source passes through.
            return Optional.of(PinnedParamsTool.wrap(agentTool,
                    RequiredParamsTool.wrap(agentTool,
                            AliasTool.wrap(agentTool, factory.create(agentTool, scope)))));
        }

        for (MultiToolProvider provider : providers) {
            if (!provider.toolNames().contains(registryName)) {
                continue;
            }
            Optional<Tool> built = provider.create(registryName, agentTool, scope);
            if (built.isEmpty()) {
                log.error("MultiToolProvider {} claimed '{}' but returned empty on create — tool will be unavailable",
                        provider.getClass().getSimpleName(), registryName);
            }
            return built.map(tool -> PinnedParamsTool.wrap(agentTool,
                    RequiredParamsTool.wrap(agentTool, AliasTool.wrap(agentTool, tool))));
        }

        log.error("Tool '{}' is configured on agent but has no registered implementation — tool will be unavailable",
                agentTool.name());
        return Optional.empty();
    }
}
