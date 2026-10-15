package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerNamespaceTest {

    @Test
    void eachNamespaceGetsAValueOfItsOwn() {
        AtomicReference<Scope> current = new AtomicReference<>(Scope.of(new Namespace("a")));
        PerNamespace<List<String>> lists = new PerNamespace<>(current::get, ArrayList::new);

        lists.current().add("in a");
        current.set(Scope.of(new Namespace("b")));

        assertThat(lists.current()).isEmpty();
        current.set(Scope.of(new Namespace("a")));
        assertThat(lists.current()).containsExactly("in a");
    }

    @Test
    void aScopeThatCannotAnswerFailsTheCall() {
        PerNamespace<List<String>> lists = new PerNamespace<>(() -> {
            throw new IllegalStateException("No scope is bound");
        }, ArrayList::new);

        assertThatThrownBy(lists::current).isInstanceOf(IllegalStateException.class);
    }
}
