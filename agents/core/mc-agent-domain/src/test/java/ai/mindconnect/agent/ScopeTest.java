package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScopeTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final UserId DAVID = UserId.of("david");

    @Test
    void localIsTheDefaultNamespaceOnNobodysBehalf() {
        Scope scope = Scope.local();

        assertThat(scope.namespace()).isEqualTo(Namespace.DEFAULT);
        assertThat(scope.userIfAny()).isEmpty();
        assertThat(scope.attributes()).isEmpty();
    }

    @Test
    void needsANamespace() {
        assertThatThrownBy(() -> new Scope(null, null, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withersBuildNewValues() {
        Scope base = Scope.of(ACME);

        Scope withUser = base.withUser(DAVID);
        Scope withGroup = withUser.withAttribute("group", "sales");

        assertThat(base.userIfAny()).isEmpty();
        assertThat(withUser.userIfAny()).contains(DAVID);
        assertThat(withGroup.attribute("group")).contains("sales");
        assertThat(withUser.attributes()).isEmpty();
        assertThat(withGroup.withNamespace(Namespace.DEFAULT).namespace()).isEqualTo(Namespace.DEFAULT);
    }

    @Test
    void attributesAreCopiedAndImmutable() {
        var attributes = new java.util.HashMap<String, String>();
        attributes.put("group", "sales");
        Scope scope = new Scope(ACME, null, attributes);
        attributes.put("group", "changed");

        assertThat(scope.attribute("group")).contains("sales");
        assertThatThrownBy(() -> scope.attributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void printsAsAPath() {
        assertThat(Scope.of(ACME).toString()).isEqualTo("acme");
        assertThat(Scope.of(ACME, DAVID).toString()).isEqualTo("acme/david");
        assertThat(Scope.of(ACME, DAVID).withAttribute("group", "sales").toString()).isEqualTo("acme/david{group=sales}");
    }
}
