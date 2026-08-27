package ai.mindconnect.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NamespaceTest {

    @Test
    void createsNamespace() {
        var ns = new Namespace("acme");
        assertThat(ns.value()).isEqualTo("acme");
        assertThat(ns.toString()).isEqualTo("acme");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> new Namespace(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Namespace(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultNamespace() {
        assertThat(Namespace.DEFAULT.value()).isEqualTo("default");
    }
}
