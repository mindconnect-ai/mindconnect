package ai.mindconnect.agent.runtime.feature;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRuntimeBeansTest {

    interface Greeter { String greet(); }

    @Test
    void aBeanIsBuiltOnceOnFirstUse() {
        var beans = new DefaultRuntimeBeans();
        var builds = new AtomicInteger();
        beans.register(Greeter.class, () -> { builds.incrementAndGet(); return () -> "hi"; });

        assertThat(builds).hasValue(0);
        assertThat(beans.get(Greeter.class).greet()).isEqualTo("hi");
        assertThat(beans.get(Greeter.class)).isSameAs(beans.get(Greeter.class));
        assertThat(builds).hasValue(1);
    }

    @Test
    void decoratorsStackInRegistrationOrderOutermostLast() {
        var beans = new DefaultRuntimeBeans();
        beans.decorate(Greeter.class, inner -> () -> "[" + inner.greet() + "]");   // before the bean exists
        beans.register(Greeter.class, () -> () -> "hi");
        beans.decorate(Greeter.class, inner -> () -> "<" + inner.greet() + ">");

        assertThat(beans.get(Greeter.class).greet()).isEqualTo("<[hi]>");
    }

    @Test
    void aLaterRegistrationReplacesTheFactoryButKeepsTheDecorators() {
        var beans = new DefaultRuntimeBeans();
        beans.register(Greeter.class, () -> () -> "default");
        beans.decorate(Greeter.class, inner -> () -> "[" + inner.greet() + "]");
        beans.register(Greeter.class, () -> () -> "configured");

        assertThat(beans.get(Greeter.class).greet()).isEqualTo("[configured]");
    }

    @Test
    void contributionsAreAListInOrder() {
        var beans = new DefaultRuntimeBeans();
        assertThat(beans.all(String.class)).isEmpty();
        beans.contribute(String.class, "a");
        beans.contribute(String.class, "b");
        assertThat(beans.all(String.class)).containsExactly("a", "b");
    }

    @Test
    void aMissingBeanNamesItsType() {
        var beans = new DefaultRuntimeBeans();
        assertThat(beans.find(Greeter.class)).isEmpty();
        assertThat(beans.has(Greeter.class)).isFalse();
        assertThatThrownBy(() -> beans.get(Greeter.class))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("Greeter");
    }

    @Test
    void aCycleIsReportedWithItsChain() {
        var beans = new DefaultRuntimeBeans();
        beans.register(Greeter.class, () -> { beans.get(List.class); return () -> ""; });
        beans.register(List.class, () -> { beans.get(Greeter.class); return new ArrayList<>(); });

        assertThatThrownBy(() -> beans.get(Greeter.class))
                .isInstanceOf(FeatureException.class)
                .hasMessageContaining("Greeter → List → Greeter");
    }

    @Test
    void afterFreezeNothingCanBeRegistered() {
        var beans = new DefaultRuntimeBeans();
        beans.register(Greeter.class, () -> () -> "hi");
        beans.freeze();
        assertThatThrownBy(() -> beans.register(String.class, () -> "x")).isInstanceOf(FeatureException.class);
        assertThatThrownBy(() -> beans.decorate(Greeter.class, g -> g)).isInstanceOf(FeatureException.class);
        assertThat(beans.get(Greeter.class).greet()).isEqualTo("hi");   // reads still work
    }

    @Test
    void aBuiltBeanCannotBeReplacedOrDecoratedAnyMore() {
        var beans = new DefaultRuntimeBeans();
        beans.register(Greeter.class, () -> () -> "hi");
        beans.get(Greeter.class);
        assertThatThrownBy(() -> beans.register(Greeter.class, () -> () -> "late"))
                .hasMessageContaining("already built");
        assertThatThrownBy(() -> beans.decorate(Greeter.class, g -> g))
                .hasMessageContaining("already built");
    }
}
