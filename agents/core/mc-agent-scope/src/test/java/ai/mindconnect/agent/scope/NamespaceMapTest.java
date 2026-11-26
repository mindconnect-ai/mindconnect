package ai.mindconnect.agent.scope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NamespaceMapTest {

    private static final Namespace A = new Namespace("a");
    private static final Namespace B = new Namespace("b");

    @Test
    void eachNamespaceGetsAValueOfItsOwn() {
        NamespaceMap<List<String>> lists = new NamespaceMap<>(ArrayList::new);

        lists.of(A).add("in a");

        assertThat(lists.of(B)).isEmpty();
        assertThat(lists.of(A)).containsExactly("in a");
    }

    @Test
    void aNamespaceKeepsTheValueMadeOnItsFirstUse() {
        NamespaceMap<List<String>> lists = new NamespaceMap<>(ArrayList::new);

        assertThat(lists.of(A)).isSameAs(lists.of(A));
    }
}
