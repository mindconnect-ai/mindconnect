package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerNamespaceTest {

    @Test
    void eachNamespaceGetsAValueOfItsOwn() {
        ThreadBoundScope scope = ThreadBoundScope.strict();
        PerNamespace<List<String>> lists = new PerNamespace<>(scope, ArrayList::new);

        scope.runIn(Scope.of(new Namespace("a")), () -> lists.current().add("in a"));

        assertThat(scope.runIn(Scope.of(new Namespace("b")), lists::current)).isEmpty();
        assertThat(scope.runIn(Scope.of(new Namespace("a")), lists::current)).containsExactly("in a");
    }

    @Test
    void anUnboundCallOnAStrictScopeFails() {
        PerNamespace<List<String>> lists = new PerNamespace<>(ThreadBoundScope.strict(), ArrayList::new);

        assertThatThrownBy(lists::current).isInstanceOf(IllegalStateException.class);
    }
}
