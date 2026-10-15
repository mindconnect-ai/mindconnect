package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.jdbc.Sql;

import java.util.Set;

/**
 * The demo's runtime feature — found through
 * {@code META-INF/services/ai.mindconnect.agent.runtime.feature.RuntimeFeature},
 * named in the manifest under {@code contributes.features}. It registers beans
 * of its own ({@link LlmCallCounter}, and the {@link DiceRollRepository} on
 * the persistence the host runs on, in the namespace of the call) and decorates one
 * core port ({@link LlmCallTraceRepository}) — the things a jar in the process
 * can do that no remote extension can, and what {@code contributes.decorates}
 * and {@code contributes.persistence} declare.
 */
public final class DemoFeature extends ConfigurableFeature {

    @Override
    public String name() {
        return "demo-dungeon";
    }

    @Override
    public Set<Class<? extends RuntimeFeature>> dependsOn() {
        return Set.of();
    }

    @Override
    protected void install(FeatureContext ctx) {
        ctx.bean(LlmCallCounter.class, LlmCallCounter::new);
        ctx.bean(DiceRollRepository.class, () -> store(ctx.persistence(), scope(ctx)));
        // The generic stores behind a record each: a bean lookup needs a class of its own.
        ctx.bean(ScenarioStore.class, () -> new ScenarioStore(documents(ctx, Scenario.class, "scenario", Scenario::id)));
        ctx.bean(AdventureStore.class, () -> new AdventureStore(documents(ctx, Adventure.class, "adventure", Adventure::id)));
        ctx.decorate(LlmCallTraceRepository.class,
                store -> new CountingTraceRepository(store, ctx.require(LlmCallCounter.class)));
    }

    private static <T> DemoStore<T> documents(FeatureContext ctx, Class<T> type, String kind,
                                              java.util.function.Function<T, String> id) {
        return switch (ctx.persistence()) {
            case Persistence.File f -> DemoStore.onFiles(f.dataDir(), scope(ctx), ctx.objectMapper(), type, kind, id);
            case Persistence.Postgres pg -> DemoStore.onPostgres(Sql.of(pg.dataSource()), scope(ctx), type, kind, id);
            case Persistence.InMemory m -> DemoStore.inMemory(id);
        };
    }

    /** The host's scope; a context without one works in the default namespace. */
    private static ScopeSupplier scope(FeatureContext ctx) {
        return ctx.find(ScopeSupplier.class).orElseGet(ScopeSupplier::local);
    }

    /** The store on whatever the host persists with, in the namespace the scope names per call. */
    private static DiceRollRepository store(Persistence persistence, ScopeSupplier scope) {
        return switch (persistence) {
            case Persistence.File f -> new FileDiceRollRepository(f.dataDir(), scope);
            case Persistence.Postgres pg -> new PgDiceRollRepository(Sql.of(pg.dataSource()), scope);
            case Persistence.InMemory m -> new InMemoryDiceRollRepository();
        };
    }
}
