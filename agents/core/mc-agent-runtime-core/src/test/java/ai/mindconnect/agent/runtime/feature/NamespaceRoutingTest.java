package ai.mindconnect.agent.runtime.feature;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ThreadBoundScope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceRoutingTest {

    public interface Store { String namespace(); }

    @Test
    void fixedBuildsOnceForTheOneNamespace() {
        var routing = NamespaceRouting.fixed(new Namespace("acme"));
        var builds = new AtomicInteger();
        Store store = routing.route(Store.class, ns -> { builds.incrementAndGet(); return ns::value; });

        assertThat(store.namespace()).isEqualTo("acme");
        assertThat(builds).hasValue(1);
        assertThat(routing.fixedNamespace()).isEqualTo(new Namespace("acme"));
    }

    @Test
    void perScopePicksTheAdapterOfTheCurrentCallAndKeepsOnePerNamespace() {
        var scope = ThreadBoundScope.withFallback(Scope.of(new Namespace("local")));
        var routing = NamespaceRouting.perScope(scope);
        var builds = new AtomicInteger();
        Store store = routing.route(Store.class, ns -> { builds.incrementAndGet(); return ns::value; });

        assertThat(store.namespace()).isEqualTo("local");
        assertThat(scope.runIn(Scope.of(new Namespace("a")), store::namespace)).isEqualTo("a");
        assertThat(scope.runIn(Scope.of(new Namespace("a")), store::namespace)).isEqualTo("a");
        assertThat(scope.runIn(Scope.of(new Namespace("b")), store::namespace)).isEqualTo("b");
        assertThat(builds).hasValue(3);   // local, a, b — a was built once
        assertThat(routing.fixedNamespace()).isNull();
    }
}
