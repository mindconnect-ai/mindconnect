package ai.mindconnect.extension.demo;

import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.jackson.JsonMapperFactory;

/**
 * The demo's plugin — found through
 * {@code META-INF/services/ai.mindconnect.plugin.Plugin}, named in the
 * manifest under {@code contributes.features}. It provides services of its
 * own ({@link LlmCallCounter}, and the {@link DiceRollRepository} on what
 * the host persists with, in the namespace of the call) and decorates one
 * core port ({@link LlmCallTraceRepository}) — the things a jar in the
 * process can do that no remote extension can, and what
 * {@code contributes.decorates} and {@code contributes.persistence} declare.
 */
public final class DemoPlugin implements Plugin {

    @Override
    public String name() {
        return "demo-dungeon";
    }

    @Override
    public void install(PluginRegistry registry) {
        LlmCallCounter counter = new LlmCallCounter();
        registry.provide(LlmCallCounter.class, () -> counter);
        registry.provide(DiceRollRepository.class, () -> rolls(registry));
        // The generic stores behind a record each: a service lookup needs a class of its own.
        registry.provide(ScenarioStore.class,
                () -> new ScenarioStore(documents(registry, Scenario.class, "scenario", Scenario::id)));
        registry.provide(AdventureStore.class,
                () -> new AdventureStore(documents(registry, Adventure.class, "adventure", Adventure::id)));
        // Decorating is providing again around what was provided: the host's store is asked for
        // now, before this plugin's answer takes its place. A host that keeps no traces has none to count.
        registry.find(LlmCallTraceRepository.class).ifPresent(traces ->
                registry.provide(LlmCallTraceRepository.class, () -> new CountingTraceRepository(traces, counter)));
    }

    private static <T> DemoStore<T> documents(PluginRegistry registry, Class<T> type, String kind,
                                              java.util.function.Function<T, String> id) {
        Optional<Sql> sql = registry.find(Sql.class);
        if (sql.isPresent()) {
            return DemoStore.onPostgres(sql.get(), scope(registry), type, kind, id);
        }
        return dataDir(registry)
                .map(dir -> DemoStore.onFiles(dir, scope(registry), JSON, type, kind, id))
                .orElseGet(() -> DemoStore.inMemory(id));
    }

    /** The store on whatever the host persists with, in the namespace the scope names per call. */
    private static DiceRollRepository rolls(PluginRegistry registry) {
        Optional<Sql> sql = registry.find(Sql.class);
        if (sql.isPresent()) {
            return new PgDiceRollRepository(sql.get(), scope(registry));
        }
        return dataDir(registry)
                .<DiceRollRepository>map(dir -> new FileDiceRollRepository(dir, scope(registry)))
                .orElseGet(InMemoryDiceRollRepository::new);
    }

    /** The host's scope; a host without one works in the default namespace. */
    private static ScopeSupplier scope(PluginRegistry registry) {
        return registry.find(ScopeSupplier.class).orElseGet(ScopeSupplier::local);
    }

    /** Where the host keeps its files, as it tells its tools; a host that tells none keeps nothing on disk. */
    private static Optional<Path> dataDir(PluginRegistry registry) {
        return registry.find(ToolEnvironment.class).flatMap(env -> env.getString("dataBaseDir")).map(Path::of);
    }

    private static final ObjectMapper JSON = JsonMapperFactory.create().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
}
