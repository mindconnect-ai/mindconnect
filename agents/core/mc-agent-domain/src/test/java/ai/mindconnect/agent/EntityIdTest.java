package ai.mindconnect.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityIdTest {

    @Test
    void aUuidStringIsAValidValue() {
        String uuid = EntityId.randomValue();
        assertThat(SessionId.of(uuid).value()).isEqualTo(uuid);
    }

    @Test
    void aReadableNameIsAValidValue() {
        assertThat(AgentId.of("web-researcher").value()).isEqualTo("web-researcher");
        assertThat(AgentId.of("file-1a2b")).hasToString("file-1a2b");
    }

    @Test
    void theValueHasToBeASafeFileName() {
        for (String bad : new String[]{null, "", "Web-Researcher", "../etc", "a b", ".hidden", "a/b"}) {
            assertThatThrownBy(() -> SessionId.of(bad))
                    .as("value '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
