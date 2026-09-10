package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binding providers is no longer part of starting up. The registry is usable
 * at once, each provider joins the catalog when it is ready, and one that
 * fails for a passing reason gets another go.
 */
class SpiToolRegistryWarmUpTest {

    /** Nothing in these tests asks the environment for anything. */
    private static final ToolEnvironment EMPTY = new ToolEnvironment() {
        @Override public <T> Optional<T> get(Class<T> type) { return Optional.empty(); }
        @Override public Optional<String> getString(String key) { return Optional.empty(); }
    };

    private SpiToolRegistry registry;

    @BeforeEach
    void setUp() {
        WarmUpProviders.reset();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.close();
    }

    @Test
    void theRegistryIsBuiltWithoutWaitingForItsProviders() {
        WarmUpProviders.slowBindMillis = 2_000;

        long start = System.currentTimeMillis();
        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[0]);
        long took = System.currentTimeMillis() - start;

        // The slow provider is still binding — the constructor did not wait.
        assertThat(took).as("construction is not the place to wait").isLessThan(500L);
        assertThat(registry.knownToolNames()).doesNotContain("slow_tool");
    }

    @Test
    void anInProcessProviderIsThereWhenTheConstructorReturns() {
        // The grace window: a provider that binds in microseconds must be
        // usable on the next line, or the Spring-free builder would hand out
        // a runtime whose tools appear a moment later.
        WarmUpProviders.slowBindMillis = 5_000;

        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[0]);

        assertThat(registry.knownToolNames()).contains("fast_tool");
    }

    @Test
    void aProviderJoinsTheCatalogWhenItIsReady() {
        WarmUpProviders.slowBindMillis = 200;

        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[0]);

        assertThat(within(5_000, () -> registry.knownToolNames().contains("slow_tool")))
                .as("the provider joined once it was ready").isTrue();
        // And it arrives in its group, so a catalog shows it where it belongs.
        assertThat(registry.toolNamesByGroup()).containsKey("warmup-slow");
    }

    @Test
    void aProviderThatFailsForAPassingReasonIsTriedAgain() {
        WarmUpProviders.flakyFailures = 2;

        // Two short delays: the third attempt is the one that works.
        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[]{ 50, 50 });

        assertThat(within(5_000, () -> registry.knownToolNames().contains("flaky_tool")))
                .as("the third attempt carried it").isTrue();
        assertThat(WarmUpProviders.flakyBinds.get()).isEqualTo(3);
    }

    @Test
    void aProviderThatNeverComesUpIsSimplyNotOffered() {
        WarmUpProviders.flakyFailures = 99;

        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[]{ 20, 20 });

        // Three attempts, then it is left alone — and nothing it would have
        // contributed shows up in the catalog.
        assertThat(within(5_000, () -> WarmUpProviders.flakyBinds.get() >= 3)).isTrue();
        assertThat(registry.knownToolNames()).doesNotContain("flaky_tool");
    }

    @Test
    void aProviderThatCannotSayWhetherItIsReadyDoesNotBreakTheCatalogue() {
        WarmUpProviders.slowBindMillis = 10;

        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[0]);

        // The throwing provider is skipped and everyone else is still served —
        // this runs on every lookup, so one broken bundle must not take the
        // catalogue with it.
        assertThat(within(5_000, () -> registry.knownToolNames().contains("slow_tool"))).isTrue();
        assertThat(registry.knownToolNames()).contains("fast_tool").doesNotContain("throwing_tool");
        assertThat(registry.toolNamesByGroup()).doesNotContainKey("warmup-throwing");
    }

    @Test
    void aDeferredRegistryBindsNothingUntilItIsAsked() throws Exception {
        WarmUpProviders.slowBindMillis = 10;

        registry = SpiToolRegistry.deferred(EMPTY);
        Thread.sleep(200);

        assertThat(registry.knownToolNames())
                .as("nothing bound before the host says so").isEmpty();

        registry.warmUp();

        assertThat(registry.knownToolNames()).contains("fast_tool");
        registry.warmUp();   // twice is a no-op, not a second round
        assertThat(WarmUpProviders.slowBinds.get()).isEqualTo(1);
    }

    /** Polls until the condition holds, or the milliseconds run out. */
    private static boolean within(long millis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void closingStopsTheWarmUp() throws Exception {
        WarmUpProviders.slowBindMillis = 5_000;
        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[]{ 5_000 });

        registry.close();
        Thread.sleep(200);

        // The slow bind was interrupted, so the bundle never became available
        // — and no thread is left sleeping on a retry.
        assertThat(registry.knownToolNames()).doesNotContain("slow_tool");
    }
}
