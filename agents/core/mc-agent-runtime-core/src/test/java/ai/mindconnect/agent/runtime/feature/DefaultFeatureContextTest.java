package ai.mindconnect.agent.runtime.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFeatureContextTest {

    private static DefaultFeatureContext context(DefaultRuntimeBeans beans, FeatureRegistry features) {
        RuntimeView view = new RuntimeView() {
            @Override public RuntimeBeans beans() { return beans; }
            @Override public Features features() { return features; }
        };
        return new DefaultFeatureContext(view, beans, Persistence.inMemory(Path.of("/tmp/x")), new ObjectMapper());
    }

    @Test
    void aFeatureReadsAnotherFeaturesBeansThroughTheRuntime() {
        var beans = new DefaultRuntimeBeans();
        var features = new FeatureRegistry();
        var ctx = context(beans, features);
        ctx.instance(String.class, "shared");

        assertThat(ctx.require(String.class)).isEqualTo("shared");
        assertThat(ctx.runtime().beans().get(String.class)).isEqualTo("shared");
        assertThat(ctx.find(Integer.class)).isEmpty();
        assertThat(ctx.persistence()).isInstanceOf(Persistence.InMemory.class);
    }

    @Test
    void propertiesAreSharedStringsNullRemoves() {
        var ctx = context(new DefaultRuntimeBeans(), new FeatureRegistry());
        ctx.property("toolsBaseDir", "/tools");
        assertThat(ctx.property("toolsBaseDir")).contains("/tools");
        assertThat(ctx.properties()).containsEntry("toolsBaseDir", "/tools");
        ctx.property("toolsBaseDir", null);
        assertThat(ctx.property("toolsBaseDir")).isEmpty();
    }

    @Test
    void startHooksRunInOrderCloseHooksInReverseAndAllOfThem() {
        var ctx = context(new DefaultRuntimeBeans(), new FeatureRegistry());
        List<String> log = new ArrayList<>();
        ctx.onStart(() -> log.add("start-1"));
        ctx.onStart(() -> log.add("start-2"));
        ctx.onClose(() -> log.add("close-1"));
        ctx.onClose(() -> { throw new IllegalStateException("boom"); });
        ctx.onClose(() -> log.add("close-3"));

        ctx.start();
        assertThatThrownBy(ctx::close).isInstanceOf(FeatureException.class).hasMessageContaining("1 close hook");

        assertThat(log).containsExactly("start-1", "start-2", "close-3", "close-1");
    }
}
