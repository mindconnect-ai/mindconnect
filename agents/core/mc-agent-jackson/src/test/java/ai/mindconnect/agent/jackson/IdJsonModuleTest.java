package ai.mindconnect.agent.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.json.JsonMapperFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

/**
 * The module as a mapper of the installation gets it: through the
 * ServiceLoader. How each id stands in JSON is held by the format tests of
 * mc-agent-management; this checks that the module is what makes it so.
 */
class IdJsonModuleTest {

    private final ObjectMapper json = JsonMapperFactory.create();

    @Test
    void a_mapper_of_the_factory_has_the_module() {
        assertThat(json.getRegisteredModuleIds()).contains("mindconnect-ids");
    }

    @Test
    void an_id_is_its_plain_value_also_as_a_key() throws Exception {
        assertThat(json.writeValueAsString(Map.of(SessionId.of("s1"), AgentId.of("a1"))))
                .isEqualTo("{\"s1\":\"a1\"}");
        assertThat(json.readValue("{\"s1\":\"a1\"}", new TypeReference<Map<SessionId, AgentId>>() { }))
                .isEqualTo(Map.of(SessionId.of("s1"), AgentId.of("a1")));
    }

    @Test
    void a_refused_value_fails_as_a_mapping_error() {
        assertThatThrownBy(() -> json.readValue("\"Not An Id\"", SessionId.class))
                .isInstanceOf(JsonMappingException.class).hasMessageContaining("not a valid id");
        assertThatThrownBy(() -> json.readValue("{\"Not An Id\":1}", new TypeReference<Map<SessionId, Integer>>() { }))
                .isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(() -> json.readValue("{\"value\":\"acme\"}", Namespace.class))
                .isInstanceOf(MismatchedInputException.class);
    }
}
