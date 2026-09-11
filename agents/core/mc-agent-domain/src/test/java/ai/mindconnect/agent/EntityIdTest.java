package ai.mindconnect.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityIdTest {

    record Holder(SessionId session, AgentId agent, UserId user) { }

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

    @Test
    void inJsonAnIdIsItsValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Holder holder = new Holder(SessionId.of("s-1"), AgentId.of("web-researcher"), UserId.of("David"));

        String json = mapper.writeValueAsString(holder);

        assertThat(json).isEqualTo("{\"session\":\"s-1\",\"agent\":\"web-researcher\",\"user\":\"David\"}");
        assertThat(mapper.readValue(json, Holder.class)).isEqualTo(holder);
    }
}
