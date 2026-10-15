package ai.mindconnect.kernel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFeatureContextTest {

    private static DefaultFeatureContext context(DefaultBeans beans, FeatureRegistry features) {
        Kernel view = new Kernel() {
            @Override public Beans beans() { return beans; }
            @Override public Features features() { return features; }
        };
        return new DefaultFeatureContext(view, beans);
    }

    @Test
    void aFeatureReadsAnotherFeaturesBeansThroughTheKernel() {
        var beans = new DefaultBeans();
        var features = new FeatureRegistry();
        var ctx = context(beans, features);
        ctx.instance(String.class, "shared");

        assertThat(ctx.require(String.class)).isEqualTo("shared");
        assertThat(ctx.kernel().beans().get(String.class)).isEqualTo("shared");
        assertThat(ctx.find(Integer.class)).isEmpty();
    }

    @Test
    void propertiesAreSharedStringsNullRemoves() {
        var ctx = context(new DefaultBeans(), new FeatureRegistry());
        ctx.property("toolsBaseDir", "/tools");
        assertThat(ctx.property("toolsBaseDir")).contains("/tools");
        assertThat(ctx.properties()).containsEntry("toolsBaseDir", "/tools");
        ctx.property("toolsBaseDir", null);
        assertThat(ctx.property("toolsBaseDir")).isEmpty();
    }

    @Test
    void startHooksRunInOrderCloseHooksInReverseAndAllOfThem() {
        var ctx = context(new DefaultBeans(), new FeatureRegistry());
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
