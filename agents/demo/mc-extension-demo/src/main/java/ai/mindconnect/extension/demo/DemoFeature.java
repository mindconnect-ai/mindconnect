package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
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
 * the persistence the host runs on, routed per namespace) and decorates one
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
        NamespaceRouting routing = ctx.find(NamespaceRouting.class)
                .orElseGet(() -> NamespaceRouting.fixed(Namespace.DEFAULT));
        ctx.bean(DiceRollRepository.class, () -> routing.route(DiceRollRepository.class, ns -> store(ctx.persistence(), ns)));
        // The generic stores behind a record each: a routed proxy needs an interface, a bean lookup a class of its own.
        ctx.bean(ScenarioStore.class, () -> new ScenarioStore(routed(routing, ctx, Scenario.class, "scenario", Scenario::id)));
        ctx.bean(AdventureStore.class, () -> new AdventureStore(routed(routing, ctx, Adventure.class, "adventure", Adventure::id)));
        ctx.decorate(LlmCallTraceRepository.class,
                store -> new CountingTraceRepository(store, ctx.require(LlmCallCounter.class)));
    }

    @SuppressWarnings("unchecked")
    private static <T> DemoStore<T> routed(NamespaceRouting routing, FeatureContext ctx, Class<T> type, String kind,
                                           java.util.function.Function<T, String> id) {
        return routing.route(DemoStore.class, ns -> switch (ctx.persistence()) {
            case Persistence.File f -> DemoStore.onFiles(f.dataDir(), ns, ctx.objectMapper(), type, kind, id);
            case Persistence.Postgres pg -> DemoStore.onPostgres(Sql.of(pg.dataSource()), ns, type, kind, id);
            case Persistence.InMemory m -> DemoStore.inMemory(id);
        });
    }

    /** The store for one namespace, on whatever the host persists with. */
    private static DiceRollRepository store(Persistence persistence, Namespace namespace) {
        return switch (persistence) {
            case Persistence.File f -> new FileDiceRollRepository(f.dataDir(), namespace);
            case Persistence.Postgres pg -> new PgDiceRollRepository(Sql.of(pg.dataSource()), namespace);
            case Persistence.InMemory m -> new InMemoryDiceRollRepository();
        };
    }
}
