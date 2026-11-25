package ai.mindconnect.agent.management.format;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Agent files as installations have them: two seed files in the form written
 * before 1.0, and what the store holds of them. What a file says must stay
 * what it says — its ids above all, which are plain strings.
 *
 * <p>Compared as JSON, not as text: the entries of an agent's metadata are
 * written in an order that differs from one start of the JVM to the next.
 */
class AgentFileFormatTest {

    /** Its modules found as every mapper of the installation finds them: through the {@code ServiceLoader}. */
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentJson files = new AgentJson(json);

    @ParameterizedTest
    @ValueSource(strings = {"default-chat", "title-generator"})
    void a_file_of_the_earlier_form_is_stored_as_it_always_was(String agent) throws Exception {
        String stored = files.write(files.read(resource(agent + ".earlier.json")));

        assertThat(json.readTree(stored)).isEqualTo(json.readTree(resource(agent + ".stored.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default-chat", "title-generator"})
    void a_stored_file_read_and_written_again_says_the_same(String agent) throws Exception {
        String stored = resource(agent + ".stored.json");

        assertThat(json.readTree(files.write(files.read(stored)))).isEqualTo(json.readTree(stored));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default-chat", "title-generator"})
    void the_ids_of_a_stored_file_are_plain_strings(String agent) throws Exception {
        JsonNode written = json.readTree(files.write(files.read(resource(agent + ".stored.json"))));

        assertThat(written.get("id").isTextual()).isTrue();
        assertThat(written.at("/definition/metadata/id").isTextual()).isTrue();
        assertThat(written.findValues("value")).as("no id wrapped in an object").isEmpty();
    }

    private static String resource(String name) {
        try (InputStream in = AgentFileFormatTest.class.getResourceAsStream("/agent-files/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
