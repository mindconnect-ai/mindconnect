package ai.mindconnect.adminui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a REST client reads of an agent: its ids as plain strings, through the
 * mapper the application's web layer writes with — not only through a mapper
 * a test builds itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mindconnect.encryption.secret-key=test-key-32-characters-long-abcd",
        "mindconnect.auth.enabled=false"
})
class RestIdFormatTest {

    @DynamicPropertySource
    static void tempDataDir(DynamicPropertyRegistry registry) {
        try {
            var dir = Files.createTempDirectory("mc-rest-id-format-test");
            registry.add("mindconnect.data.base-dir", dir::toString);
            registry.add("mindconnect.tools.base-dir", dir::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void an_agent_in_the_list_carries_its_ids_as_strings() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/api/agents", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode agents = new ObjectMapper().readTree(response.getBody());
        assertThat(agents.isArray()).isTrue();
        assertThat(agents).as("the seeded agents").isNotEmpty();
        for (JsonNode agent : agents) {
            assertThat(agent.get("id").isTextual()).as("id of %s", agent.at("/definition/name")).isTrue();
            assertThat(agent.at("/definition/metadata/id").isTextual()).isTrue();
        }
        assertThat(response.getBody()).as("no id wrapped in an object").doesNotContain("\"value\"");
    }

    @Test
    void one_agent_read_by_its_id_carries_it_as_a_string() throws Exception {
        String id = new ObjectMapper().readTree(http.getForEntity("/api/agents", String.class).getBody())
                .get(0).get("id").asText();

        ResponseEntity<String> response = http.getForEntity("/api/agents/" + id, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"id\":\"" + id + "\"").doesNotContain("\"value\"");
    }
}
