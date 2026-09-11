package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamespacedIdTest {

    private static final Namespace ACME = new Namespace("acme");

    record TestId(Namespace namespace, String value) implements NamespacedId {
        TestId {
            NamespacedId.check(namespace, value);
        }
    }

    @Test
    void aUuidStringIsAValidValue() {
        String uuid = NamespacedId.randomValue();
        assertThat(new TestId(ACME, uuid).value()).isEqualTo(uuid);
    }

    @Test
    void aReadableNameIsAValidValue() {
        assertThat(new TestId(ACME, "web-researcher").qualified()).isEqualTo("acme/web-researcher");
        assertThat(new TestId(ACME, "file-1a2b").value()).isEqualTo("file-1a2b");
    }

    @Test
    void theValueHasToBeASafeFileName() {
        for (String bad : new String[]{null, "", "Web-Researcher", "../etc", "a b", ".hidden", "a/b"}) {
            assertThatThrownBy(() -> new TestId(ACME, bad))
                    .as("value '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theNamespaceIsRequired() {
        assertThatThrownBy(() -> new TestId(null, "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameValueInTwoNamespacesAreTwoIds() {
        TestId a = new TestId(ACME, "default");
        TestId b = new TestId(new Namespace("globex"), "default");
        assertThat(a).isNotEqualTo(b);
        assertThat(a.in(ACME)).isTrue();
        assertThat(b.in(ACME)).isFalse();
    }
}
