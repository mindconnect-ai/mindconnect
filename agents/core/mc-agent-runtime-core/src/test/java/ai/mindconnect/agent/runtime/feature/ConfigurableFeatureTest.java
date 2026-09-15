package ai.mindconnect.agent.runtime.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurableFeatureTest {

    static class Greeting extends ConfigurableFeature {
        String text = "hi";
        Greeting text(String text) { changing(); this.text = text; return this; }
        @Override public String name() { return "greeting"; }
        @Override protected void install(FeatureContext ctx) { ctx.instance(String.class, text); }
    }

    private static FeatureContext context(DefaultRuntimeBeans beans) {
        var features = new FeatureRegistry();
        RuntimeView view = new RuntimeView() {
            @Override public RuntimeBeans beans() { return beans; }
            @Override public Features features() { return features; }
        };
        return new DefaultFeatureContext(view, beans, Persistence.inMemory(Path.of("/tmp/x")), new ObjectMapper());
    }

    @Test
    void settingsAreReadWhenConfiguredAndFrozenAfterwards() {
        var beans = new DefaultRuntimeBeans();
        var greeting = new Greeting().text("hello");
        assertThat(greeting.configured()).isFalse();

        greeting.configure(context(beans));

        assertThat(beans.get(String.class)).isEqualTo("hello");
        assertThat(greeting.configured()).isTrue();
        assertThatThrownBy(() -> greeting.text("late"))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("Greeting")
                .hasMessageContaining("cannot change");
        assertThatThrownBy(() -> greeting.configure(context(new DefaultRuntimeBeans())))
                .isInstanceOf(FeatureException.class);   // one runtime per instance
    }
}
