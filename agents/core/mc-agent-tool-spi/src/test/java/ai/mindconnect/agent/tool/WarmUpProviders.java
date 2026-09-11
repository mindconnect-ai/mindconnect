package ai.mindconnect.agent.tool;


import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The providers the warm-up test drives, registered through
 * {@code META-INF/services} so they arrive the way real ones do — through the
 * ServiceLoader, not through a constructor.
 *
 * <p>They talk to each other through static state on purpose: the registry
 * builds its own instances, so a test can only reach them from the outside.
 * {@link #reset()} puts that state back between tests.
 */
public final class WarmUpProviders {

    private WarmUpProviders() {
    }

    /** How long {@link Slow#bind} takes before the bundle is ready. */
    static volatile long slowBindMillis = 300;
    /** How many attempts {@link Flaky#bind} throws on before it works. */
    static volatile int flakyFailures = 2;
    /** Whether {@link Throwing} throws when asked. */
    static volatile boolean throwFromIsAvailable = true;

    static final AtomicInteger slowBinds = new AtomicInteger();
    static final AtomicInteger flakyBinds = new AtomicInteger();
    /** Sessions {@link Fast} was asked to release, in order. */
    static final java.util.List<ai.mindconnect.agent.SessionId> fastReleases =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    static void reset() {
        slowBindMillis = 300;
        flakyFailures = 2;
        throwFromIsAvailable = true;
        slowBinds.set(0);
        flakyBinds.set(0);
        fastReleases.clear();
    }

    /** Ready, but not at once — like an MCP server that has to be spawned. */
    public static final class Slow implements MultiToolProvider {
        private volatile boolean bound;

        @Override public Set<String> toolNames() { return bound ? Set.of("slow_tool") : Set.of(); }

        @Override public String group() { return "warmup-slow"; }

        @Override
        public void bind(ToolEnvironment env) {
            slowBinds.incrementAndGet();
            try {
                Thread.sleep(slowBindMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            bound = true;
        }

        @Override public boolean isAvailable() { return bound; }

        @Override
        public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }

    /** Ready the moment it is asked — like every in-process provider. */
    public static final class Fast implements MultiToolProvider {
        private volatile boolean bound;

        @Override public Set<String> toolNames() { return bound ? Set.of("fast_tool") : Set.of(); }

        @Override public String group() { return "warmup-fast"; }

        @Override public void releaseSession(ai.mindconnect.agent.SessionId sessionId) {
            fastReleases.add(sessionId);
        }

        @Override public void bind(ToolEnvironment env) { bound = true; }

        @Override public boolean isAvailable() { return bound; }

        @Override
        public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }

    /** Cannot say whether it is ready — a broken health check. */
    public static final class Throwing implements MultiToolProvider {
        @Override public Set<String> toolNames() { return Set.of("throwing_tool"); }

        @Override public String group() { return "warmup-throwing"; }

        @Override public void bind(ToolEnvironment env) { }

        @Override
        public boolean isAvailable() {
            if (throwFromIsAvailable) throw new IllegalStateException("cannot say");
            return true;
        }

        @Override
        public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }

    /** Fails a few times first — like a container runtime that starts late. */
    public static final class Flaky implements MultiToolProvider {
        private volatile boolean bound;

        @Override public Set<String> toolNames() { return bound ? Set.of("flaky_tool") : Set.of(); }

        @Override public String group() { return "warmup-flaky"; }

        @Override
        public void bind(ToolEnvironment env) {
            if (flakyBinds.incrementAndGet() <= flakyFailures) {
                throw new IllegalStateException("not yet, attempt " + flakyBinds.get());
            }
            bound = true;
        }

        @Override public boolean isAvailable() { return bound; }

        @Override
        public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
            return Optional.empty();
        }
    }
}
