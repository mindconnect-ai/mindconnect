package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.Namespace;
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
public class SpiToolRegistry implements ToolRegistry, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpiToolRegistry.class);

    /**
     * How long to wait before each further binding attempt. Four retries over
     * roughly a minute: long enough for a container runtime that starts
     * alongside the application, short enough that nobody waits on it.
     */
    private static final long[] RETRY_DELAYS = { 2_000L, 5_000L, 15_000L, 45_000L };

    /**
     * How long the constructor gives the first round of binds before it
     * returns anyway. Most providers are in-process and bind in microseconds
     * — a caller that builds a runtime and runs a turn on the next line
     * should find them there. The ones that talk to something outside miss
     * this window and join later, which is the whole point.
     */
    private static final long FIRST_ROUND_GRACE_MS = 200L;

    private final Map<String, ToolFactory> factoriesByName;
    private final List<MultiToolProvider> providers;
    private final List<Thread> warmUpThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final long[] retryDelays;
    private final ToolEnvironment environment;
    private final java.util.concurrent.atomic.AtomicBoolean warmedUp =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile java.util.concurrent.CountDownLatch firstRound =
            new java.util.concurrent.CountDownLatch(0);

    public SpiToolRegistry(ToolEnvironment environment) {
        this(environment, Thread.currentThread().getContextClassLoader());
    }

    public SpiToolRegistry(ToolEnvironment environment, ClassLoader classLoader) {
        this(environment, classLoader, RETRY_DELAYS, true);
    }

    /**
     * The registry without the warm-up: it is built, and nothing is bound
     * until {@link #warmUp()} is called.
     *
     * <p>For a host that starts in phases. Binding asks the environment for
     * services, and a host whose environment resolves those from a container
     * that is still starting would have the warm-up queue behind its own
     * startup — the Spring runtime hands this registry its providers once the
     * context is up rather than while it is coming up.
     */
    public static SpiToolRegistry deferred(ToolEnvironment environment) {
        return new SpiToolRegistry(environment, Thread.currentThread().getContextClassLoader(),
                RETRY_DELAYS, false);
    }

    /** For tests: the same registry with its own patience. */
    SpiToolRegistry(ToolEnvironment environment, ClassLoader classLoader, long[] retryDelays) {
        this(environment, classLoader, retryDelays, true);
    }

    private SpiToolRegistry(ToolEnvironment environment, ClassLoader classLoader,
                            long[] retryDelays, boolean warmUpNow) {
        this.retryDelays = retryDelays;
        this.environment = environment;
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

        // 2) Multi-tool MultiToolProvider SPI. The provider SET is what the
        //    classpath offers — every one of them is kept. What changes over
        //    time is whether a provider is ready and which names it carries,
        //    and both are asked live on every lookup. That is what lets the
        //    binding happen off this thread: a provider joins the catalog the
        //    moment it is ready, without anybody restarting anything.
        List<MultiToolProvider> discovered = new ArrayList<>();
        for (MultiToolProvider provider : ServiceLoader.load(MultiToolProvider.class, classLoader)) {
            discovered.add(provider);
        }
        this.providers = List.copyOf(discovered);

        log.info("SpiToolRegistry: {} single-tool factor(ies); warming up {} provider(s) in the background",
                factoriesByName.size(), providers.size());
        if (log.isDebugEnabled()) {
            log.debug("  factories: {}", factoriesByName.keySet());
        }
        if (warmUpNow) warmUp();
    }

    /**
     * Binds the providers, once. Returns when the first round has had its
     * moment — see {@link #FIRST_ROUND_GRACE_MS} — not when every provider is
     * ready. Calling it twice does nothing the second time.
     */
    public void warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return;
        startWarmUp();
        awaitFirstRound();
    }

    /**
     * Binds every provider off the caller's thread, one virtual thread each.
     *
     * <p>Binding can be slow and can fail for reasons that pass: an MCP server
     * behind a container needs the container runtime to be up, a remote
     * catalog needs the network. Doing it here would make every such case a
     * slow start, and doing it once would make a runtime that appears a minute
     * later useless until the next restart. So each provider gets a few
     * attempts with a widening gap, and the first one that succeeds puts the
     * bundle in the catalog.
     */
    private void startWarmUp() {
        this.firstRound = new java.util.concurrent.CountDownLatch(providers.size());
        for (MultiToolProvider provider : providers) {
            Thread worker = Thread.ofVirtual()
                    .name("tool-warmup-" + provider.getClass().getSimpleName())
                    .start(() -> bindWithRetries(provider, environment));
            warmUpThreads.add(worker);
        }
    }

    /**
     * Waits for the first attempt of every provider, but not for long. What is
     * ready by then is ready when the warm-up call returns; the rest arrives
     * while the application is already serving.
     */
    private void awaitFirstRound() {
        try {
            if (!firstRound.await(FIRST_ROUND_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.info("SpiToolRegistry: {} provider(s) still warming up — they join when ready",
                        firstRound.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One provider's attempts, until it is available or the attempts run out. */
    private void bindWithRetries(MultiToolProvider provider, ToolEnvironment environment) {
        String name = provider.getClass().getSimpleName();
        for (int attempt = 1; attempt <= retryDelays.length + 1; attempt++) {
            try {
                provider.bind(environment);
                if (provider.isAvailable()) {
                    log.info("MultiToolProvider {} is ready with {} tool(s): {}",
                            name, provider.toolNames().size(), provider.toolNames());
                    return;
                }
                // The first "not yet" is worth a line; the retries are not.
                if (attempt == 1) {
                    log.info("MultiToolProvider {} is not available yet — trying again", name);
                } else {
                    log.debug("MultiToolProvider {} still unavailable (attempt {})", name, attempt);
                }
            } catch (RuntimeException e) {
                log.warn("MultiToolProvider {} failed to bind on attempt {}: {}",
                        name, attempt, e.toString());
            } finally {
                if (attempt == 1) firstRound.countDown();
            }
            if (attempt > retryDelays.length) break;
            try {
                Thread.sleep(retryDelays[attempt - 1]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;                      // close() — stop trying, quietly
            }
        }
        log.warn("MultiToolProvider {} stayed unavailable — its tools are not offered. "
                + "Fix what it needs and restart, or leave it be if it is not wanted here.", name);
    }

    /**
     * Stops the warm-up. Virtual threads never hold a JVM open, so this is for
     * a registry that outlives its usefulness before the process does — a test,
     * or an application context that closes.
     */
    @Override
    public void close() {
        for (Thread thread : warmUpThreads) {
            thread.interrupt();
        }
    }

    @Override
    public Set<String> knownToolNames() {
        // Union of ToolFactory names + each provider's *current* names.
        // ToolFactory keys come first (their order) followed by provider keys.
        Set<String> union = new LinkedHashSet<>(factoriesByName.keySet());
        for (MultiToolProvider provider : ready()) {
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
        for (MultiToolProvider provider : ready()) {
            Set<String> names = provider.toolNames();
            if (names.isEmpty()) continue;
            byGroup.computeIfAbsent(groupOrDefault(provider.group()), g -> new java.util.TreeSet<>())
                    .addAll(names);
        }
        return byGroup;
    }

    /**
     * The providers that are ready this moment. A provider still warming up,
     * or one that gave up, contributes nothing — and contributes again as soon
     * as that changes, because nobody caches this.
     */
    private List<MultiToolProvider> ready() {
        List<MultiToolProvider> available = new ArrayList<>(providers.size());
        for (MultiToolProvider provider : providers) {
            try {
                if (provider.isAvailable()) available.add(provider);
            } catch (RuntimeException e) {
                // This runs on every lookup now, so a provider that throws
                // here would fail every turn and every catalogue render — and
                // take the other providers' tools with it. A bundle that
                // cannot say whether it is ready is not ready.
                log.warn("MultiToolProvider {} threw from isAvailable() — treating it as unavailable: {}",
                        provider.getClass().getSimpleName(), e.toString());
            }
        }
        return available;
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

        for (MultiToolProvider provider : ready()) {
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
