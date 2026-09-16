package ai.mindconnect.agent.runtime.feature;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureRegistryTest {

    static class Tools implements RuntimeFeature {
        @Override public String name() { return "tools"; }
        @Override public void configure(FeatureContext ctx) { }
    }

    static class ConfiguredTools extends Tools { }

    static class SubAgents implements RuntimeFeature {
        @Override public String name() { return "sub-agents"; }
        @Override public Set<Class<? extends RuntimeFeature>> dependsOn() { return Set.of(Tools.class); }
        @Override public void configure(FeatureContext ctx) { }
    }

    static class Workflows implements RuntimeFeature {
        @Override public String name() { return "workflows"; }
        @Override public Set<Class<? extends RuntimeFeature>> dependsOn() { return Set.of(Tools.class, SubAgents.class); }
        @Override public void configure(FeatureContext ctx) { }
    }

    static class Impostor implements RuntimeFeature {
        @Override public String name() { return "tools"; }
        @Override public void configure(FeatureContext ctx) { }
    }

    @Test
    void aMissingDependencyFailsAtInstallTimeAndNamesBothSides() {
        var registry = new FeatureRegistry();
        assertThatThrownBy(() -> registry.install(new SubAgents()))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("SubAgents")
                .hasMessageContaining("needs Tools")
                .hasMessageContaining("not installed");
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void installedDependenciesAreFoundByClassAndByName() {
        var registry = new FeatureRegistry();
        var tools = new Tools();
        registry.install(tools);
        registry.install(new SubAgents());

        assertThat(registry.get(Tools.class)).isSameAs(tools);
        assertThat(registry.byName("sub-agents")).isPresent();
        assertThat(registry.has(Workflows.class)).isFalse();
        assertThatThrownBy(() -> registry.get(Workflows.class))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("Workflows")
                .hasMessageContaining("not installed");
    }

    @Test
    void aSubclassReplacesTheDefaultOfTheSameName() {
        var registry = new FeatureRegistry();
        registry.install(new Tools());
        var configured = new ConfiguredTools();
        registry.install(configured);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get(Tools.class)).isSameAs(configured);       // dependants still find it
        assertThat(registry.get(ConfiguredTools.class)).isSameAs(configured);
    }

    @Test
    void anUnrelatedClassCannotTakeOverAName() {
        var registry = new FeatureRegistry();
        registry.install(new Tools());
        assertThatThrownBy(() -> registry.install(new Impostor()))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("cannot replace");
    }

    @Test
    void installAllOrdersByDependencies() {
        var registry = new FeatureRegistry();
        registry.installAll(List.of(new Workflows(), new SubAgents(), new Tools()));

        assertThat(registry.all()).extracting(RuntimeFeature::name)
                .containsExactly("tools", "sub-agents", "workflows");
    }

    @Test
    void installAllStillFailsWhenADependencyIsNowhere() {
        var registry = new FeatureRegistry();
        assertThatThrownBy(() -> registry.installAll(List.of(new Workflows(), new SubAgents())))
                .hasMessageContaining("needs Tools");
    }
}
