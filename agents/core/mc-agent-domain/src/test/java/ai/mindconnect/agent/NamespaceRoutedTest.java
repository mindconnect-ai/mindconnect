package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamespaceRoutedTest {

    interface Counter {
        String namespace();

        int next();

        void fail();
    }

    static final class CountingAdapter implements Counter {
        private final Namespace namespace;
        private int count;

        CountingAdapter(Namespace namespace) {
            this.namespace = namespace;
        }

        @Override public String namespace() { return namespace.value(); }

        @Override public int next() { return ++count; }

        @Override public void fail() { throw new IllegalStateException("adapter for " + namespace); }
    }

    private static final Namespace ACME = new Namespace("acme");
    private static final Namespace OTHER = new Namespace("other");

    @Test
    void forwardsToTheAdapterOfTheCurrentNamespace() {
        ThreadBoundScope scope = ThreadBoundScope.strict();
        Counter routed = NamespaceRouted.route(Counter.class, scope, CountingAdapter::new);

        String acme = scope.runIn(Scope.of(ACME), routed::namespace);
        String other = scope.runIn(Scope.of(OTHER), routed::namespace);

        assertThat(acme).isEqualTo("acme");
        assertThat(other).isEqualTo("other");
    }

    @Test
    void keepsOneInstancePerNamespace() {
        ThreadBoundScope scope = ThreadBoundScope.strict();
        List<Namespace> built = new ArrayList<>();
        Counter routed = NamespaceRouted.route(Counter.class, scope, ns -> {
            built.add(ns);
            return new CountingAdapter(ns);
        });

        scope.runIn(Scope.of(ACME), routed::next);
        scope.runIn(Scope.of(ACME), routed::next);
        int acmeCount = scope.runIn(Scope.of(ACME), routed::next);
        int otherCount = scope.runIn(Scope.of(OTHER), routed::next);

        assertThat(acmeCount).isEqualTo(3);
        assertThat(otherCount).isEqualTo(1);
        assertThat(built).containsExactly(ACME, OTHER);
    }

    @Test
    void aFixedSupplierRoutesEverythingToOneAdapter() {
        Counter routed = NamespaceRouted.route(Counter.class, ScopeSupplier.fixed(ACME), CountingAdapter::new);

        routed.next();

        assertThat(routed.next()).isEqualTo(2);
        assertThat(routed.namespace()).isEqualTo("acme");
    }

    @Test
    void anAdapterExceptionReachesTheCallerUnwrapped() {
        Counter routed = NamespaceRouted.route(Counter.class, ScopeSupplier.fixed(ACME), CountingAdapter::new);

        assertThatThrownBy(routed::fail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("adapter for acme");
    }

    @Test
    void anUnboundStrictSupplierFailsTheCallBeforeAnyAdapterIsBuilt() {
        List<Namespace> built = new ArrayList<>();
        Counter routed = NamespaceRouted.route(Counter.class, ThreadBoundScope.strict(), ns -> {
            built.add(ns);
            return new CountingAdapter(ns);
        });

        assertThatThrownBy(routed::next).isInstanceOf(IllegalStateException.class);
        assertThat(built).isEmpty();
    }

    @Test
    void evictDropsTheInstanceSoTheNextCallBuildsAFreshOne() {
        Counter routed = NamespaceRouted.route(Counter.class, ScopeSupplier.fixed(ACME), CountingAdapter::new);
        routed.next();

        NamespaceRouted.evict(routed, ACME);

        assertThat(routed.next()).isEqualTo(1);
    }

    @Test
    void objectMethodsDoNotTouchAnAdapter() {
        Counter routed = NamespaceRouted.route(Counter.class, ThreadBoundScope.strict(), CountingAdapter::new);

        assertThat(routed.toString()).startsWith("NamespaceRouted[Counter");
        assertThat(routed).isEqualTo(routed);
        assertThat(routed.hashCode()).isEqualTo(routed.hashCode());
    }

    @Test
    void onlyInterfacesCanBeRouted() {
        assertThatThrownBy(() -> NamespaceRouted.route(CountingAdapter.class, ScopeSupplier.local(), CountingAdapter::new))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
