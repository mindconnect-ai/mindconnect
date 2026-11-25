package ai.mindconnect.agent.management.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.tool.AgentToolId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;

/**
 * How the ids and the namespace stand in JSON: as their plain value, wherever
 * they occur. Stored files, database columns and REST clients read exactly
 * this, so these texts must not change — an id written as
 * {@code {"value": "…"}} is the form a mapper falls back to on its own, and
 * nothing fails when it does.
 */
class IdJsonFormatTest {

    /** Its modules found as every mapper of the installation finds them: through the {@code ServiceLoader}. */
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    record Everything(SessionId session, UserId user, AgentId agent, AgentToolId tool, Namespace namespace) { }

    private static final Everything ALL = new Everything(SessionId.of("s1"), UserId.of("alice"), AgentId.of("a1"),
            AgentToolId.of("t1"), new Namespace("acme"));

    @Test
    void each_is_written_as_its_plain_value() throws Exception {
        assertThat(json.writeValueAsString(SessionId.of("s1"))).isEqualTo("\"s1\"");
        assertThat(json.writeValueAsString(UserId.of("alice"))).isEqualTo("\"alice\"");
        assertThat(json.writeValueAsString(AgentId.of("a1"))).isEqualTo("\"a1\"");
        assertThat(json.writeValueAsString(AgentToolId.of("t1"))).isEqualTo("\"t1\"");
        assertThat(json.writeValueAsString(new Namespace("acme"))).isEqualTo("\"acme\"");
    }

    @Test
    void as_a_field_each_is_its_plain_value() throws Exception {
        assertThat(json.writeValueAsString(ALL)).isEqualTo(
                "{\"session\":\"s1\",\"user\":\"alice\",\"agent\":\"a1\",\"tool\":\"t1\",\"namespace\":\"acme\"}");
    }

    @Test
    void a_field_left_out_or_null_is_read_as_null_and_written_as_null() throws Exception {
        Everything none = json.readValue("{\"session\":null}", Everything.class);

        assertThat(none).isEqualTo(new Everything(null, null, null, null, null));
        assertThat(json.writeValueAsString(none)).isEqualTo(
                "{\"session\":null,\"user\":null,\"agent\":null,\"tool\":null,\"namespace\":null}");
    }

    @Test
    void in_a_list_each_is_its_plain_value() throws Exception {
        assertThat(json.writeValueAsString(List.of(SessionId.of("s1"), SessionId.of("s2"))))
                .isEqualTo("[\"s1\",\"s2\"]");
        assertThat(json.writeValueAsString(List.of(new Namespace("acme"), new Namespace("local"))))
                .isEqualTo("[\"acme\",\"local\"]");
    }

    @Test
    void as_the_key_of_a_map_each_is_its_plain_value() throws Exception {
        assertThat(json.writeValueAsString(Map.of(SessionId.of("s1"), 1))).isEqualTo("{\"s1\":1}");
        assertThat(json.writeValueAsString(Map.of(UserId.of("alice"), 1))).isEqualTo("{\"alice\":1}");
        assertThat(json.writeValueAsString(Map.of(AgentId.of("a1"), 1))).isEqualTo("{\"a1\":1}");
        assertThat(json.writeValueAsString(Map.of(AgentToolId.of("t1"), 1))).isEqualTo("{\"t1\":1}");
        assertThat(json.writeValueAsString(Map.of(new Namespace("acme"), 1))).isEqualTo("{\"acme\":1}");
    }

    @Test
    void what_is_written_is_read_back() throws Exception {
        assertThat(json.readValue("\"s1\"", SessionId.class)).isEqualTo(SessionId.of("s1"));
        assertThat(json.readValue("\"alice\"", UserId.class)).isEqualTo(UserId.of("alice"));
        assertThat(json.readValue("\"a1\"", AgentId.class)).isEqualTo(AgentId.of("a1"));
        assertThat(json.readValue("\"t1\"", AgentToolId.class)).isEqualTo(AgentToolId.of("t1"));
        assertThat(json.readValue("\"acme\"", Namespace.class)).isEqualTo(new Namespace("acme"));
        assertThat(json.readValue(json.writeValueAsString(ALL), Everything.class)).isEqualTo(ALL);
    }

    @Test
    void the_keys_of_a_map_are_read_back() throws Exception {
        assertThat(json.readValue("{\"s1\":1}", new TypeReference<Map<SessionId, Integer>>() { }))
                .isEqualTo(Map.of(SessionId.of("s1"), 1));
        assertThat(json.readValue("{\"alice\":1}", new TypeReference<Map<UserId, Integer>>() { }))
                .isEqualTo(Map.of(UserId.of("alice"), 1));
        assertThat(json.readValue("{\"a1\":1}", new TypeReference<Map<AgentId, Integer>>() { }))
                .isEqualTo(Map.of(AgentId.of("a1"), 1));
        assertThat(json.readValue("{\"t1\":1}", new TypeReference<Map<AgentToolId, Integer>>() { }))
                .isEqualTo(Map.of(AgentToolId.of("t1"), 1));
        assertThat(json.readValue("{\"acme\":1}", new TypeReference<Map<Namespace, Integer>>() { }))
                .isEqualTo(Map.of(new Namespace("acme"), 1));
    }

    /** What stores of the first builds on the new runtime wrote for an agent's id, and only for that. */
    @Test
    void an_agent_id_wrapped_in_an_object_is_still_read() throws Exception {
        assertThat(json.readValue("{\"value\":\"a1\"}", AgentId.class)).isEqualTo(AgentId.of("a1"));
        assertThat(json.readValue("{\"agent\":{\"value\":\"a1\"}}", Everything.class).agent())
                .isEqualTo(AgentId.of("a1"));
    }

    @Test
    void the_other_ids_and_the_namespace_wrapped_in_an_object_are_refused() {
        assertThatThrownBy(() -> json.readValue("{\"value\":\"s1\"}", SessionId.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> json.readValue("{\"value\":\"alice\"}", UserId.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> json.readValue("{\"value\":\"t1\"}", AgentToolId.class))
                .isInstanceOf(MismatchedInputException.class);
        assertThatThrownBy(() -> json.readValue("{\"value\":\"acme\"}", Namespace.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void a_value_no_id_can_have_is_refused_when_read() {
        assertThatThrownBy(() -> json.readValue("\"Not An Id\"", SessionId.class)).hasMessageContaining("not a valid id");
        assertThatThrownBy(() -> json.readValue("\"\"", UserId.class)).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> json.readValue("\" \"", Namespace.class)).hasMessageContaining("must not be blank");
    }
}
