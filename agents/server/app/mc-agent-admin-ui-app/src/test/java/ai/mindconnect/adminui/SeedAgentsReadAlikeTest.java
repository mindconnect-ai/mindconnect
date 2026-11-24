package ai.mindconnect.adminui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.starter.runtime.bridge.AgentDefinitionMapper;

/**
 * The shipped agents are files of the form the earlier runtime wrote. Read
 * as files, they are the agents the earlier runtime's own classes made of
 * them — so an installation seeded either way runs the same agents.
 */
class SeedAgentsReadAlikeTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void everyShippedAgentReadsIntoTheDefinitionTheEarlierClassesMade() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:initial-data/agent-definitions/*.json");
        assertThat(files).isNotEmpty();
        for (Resource file : files) {
            String content = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            AgentDefinition earlier = json.readValue(content, AgentDefinition.class);

            ManagedAgent read = new AgentJson(json).read(content);

            assertThat(read.definition()).as(file.getFilename())
                    .isEqualTo(new AgentDefinitionMapper().toRuntime(earlier));
            assertThat(read.id().value()).as(file.getFilename()).isEqualTo(earlier.id().value());
            assertThat(read.group()).as(file.getFilename()).isEqualTo(earlier.group());
            assertThat(read.icon()).as(file.getFilename()).isEqualTo(earlier.icon());
            assertThat(read.welcomeMessage()).as(file.getFilename()).isEqualTo(earlier.welcomeMessage());
            assertThat(read.status().name()).as(file.getFilename()).isEqualTo(earlier.status().name());
        }
    }
}
