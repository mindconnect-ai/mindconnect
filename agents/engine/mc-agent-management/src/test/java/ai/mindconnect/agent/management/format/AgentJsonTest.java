package ai.mindconnect.agent.management.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.management.api.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.tool.AgentToolBinding;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;

/**
 * An agent file is the managed agent as it is stored; a file of the form
 * the earlier runtime wrote is read into the same.
 */
class AgentJsonTest {

    private final AgentJson files = new AgentJson(new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Test
    void whatIsWrittenIsReadBackTheSame() {
        ManagedAgent owl = new ManagedAgent(AgentId.of("a1"),
                new AgentDefinition("owl", "Knows owls.", "You know owls.", ModelSettings.ofModel("small"),
                        List.of(new ToolBinding("bash", true, Map.of("needsApproval", true))), 7,
                        Map.of("subAgents", List.of("mouse"))),
                "birds", "feather", "Hoot.", ManagedAgent.Status.DRAFT,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"), 3);

        assertThat(files.read(files.write(owl))).isEqualTo(owl);
    }

    @Test
    void anIdIsItsPlainValue_andTheWrappedOneOfAnOlderStoreIsStillRead() {
        ManagedAgent owl = ManagedAgent.of(new AgentDefinition("owl", "", "", ModelSettings.NONE, List.of(), 5,
                Map.of()));
        String written = files.write(new ManagedAgent(AgentId.of("a1"), owl.definition(), null, null, null,
                ManagedAgent.Status.ACTIVE, null, null, 1));

        assertThat(written).contains("\"id\" : \"a1\"");
        assertThat(files.read(written.replace("\"id\" : \"a1\"", "\"id\" : {\"value\" : \"a1\"}")).id())
                .isEqualTo(AgentId.of("a1"));
    }

    @Test
    void anAgentOfTheEarlierFormIsReadIntoTheSameShape() {
        ManagedAgent read = files.read("""
                {"id": "a1", "name": "owl", "description": "Knows owls.", "group": "birds", "icon": "feather",
                 "systemPrompt": "You know owls.", "welcomeMessage": "Hoot.", "llmConfigName": "small",
                 "maxIterations": 7, "status": "DEPRECATED",
                 "memoryConfig": {"kind": "auto_compact", "compactAtRatio": 0.8, "summaryPlacement": "USER_MESSAGE"},
                 "responseReviewers": ["checker"], "callableAgents": ["mouse"],
                 "skills": {"mode": "SPECIFIC", "names": ["hooting"]},
                 "tools": [
                   {"id": "t1", "name": "bash", "description": "Runs.", "enabled": true, "deferred": true,
                    "needsApproval": true, "maxResultChars": 100, "overrides": {"timeout": 5}},
                   {"id": "t2", "name": "web_search", "enabled": false}],
                 "createdAt": "2025-01-01T00:00:00Z", "updatedAt": "2025-01-02T00:00:00Z", "version": 4}
                """);

        assertThat(read.id()).isEqualTo(AgentId.of("a1"));
        assertThat(read.group()).isEqualTo("birds");
        assertThat(read.icon()).isEqualTo("feather");
        assertThat(read.welcomeMessage()).isEqualTo("Hoot.");
        assertThat(read.status()).isEqualTo(ManagedAgent.Status.DEPRECATED);
        assertThat(read.createdAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(read.version()).isEqualTo(4);
        AgentDefinition definition = read.definition();
        assertThat(definition.name()).isEqualTo("owl");
        assertThat(definition.instructions()).isEqualTo("You know owls.");
        assertThat(definition.modelSettings().model()).isEqualTo("small");
        assertThat(definition.maxRounds()).isEqualTo(7);
        assertThat(definition.tools()).as("a tool switched off is left out").extracting(ToolBinding::name)
                .containsExactly("bash");
        ToolBinding bash = definition.tools().get(0);
        assertThat(bash.deferred()).isTrue();
        assertThat(bash.metadata()).containsEntry(AgentToolBinding.TOOL_ID, "t1")
                .containsEntry(AgentToolBinding.DESCRIPTION, "Runs.")
                .containsEntry(AgentToolBinding.MAX_RESULT_CHARS, 100)
                .containsEntry(AgentToolBinding.OVERRIDES, Map.of("timeout", 5))
                .containsEntry("needsApproval", true);
        assertThat(definition.metadata()).containsEntry(ManagedAgent.AGENT_ID, "a1")
                .containsEntry("subAgents", List.of("mouse"))
                .containsEntry("responseReviewers", List.of("checker"))
                .containsEntry("skills", List.of("hooting"))
                .containsEntry("memory", Map.of("strategy", "compact", "maxConversationRatio", 0.8,
                        "summaryPlacement", "user_message"));
    }

    @Test
    void anEarlierAgentOtherAgentsMayNotCallStaysSo() {
        ManagedAgent read = files.read("{\"name\": \"titles\", \"callableByAgents\": false}");

        assertThat(read.definition().metadata()).containsEntry("callableByAgents", false);
        assertThat(files.read(files.write(read)).definition().metadata()).containsEntry("callableByAgents", false);
    }

    @Test
    void anEarlierAgentThatSaysLittleGetsTheDefaults() {
        ManagedAgent read = files.read("{\"name\": \"plain\"}");

        assertThat(read.id().value()).isNotBlank();
        assertThat(read.status()).isEqualTo(ManagedAgent.Status.ACTIVE);
        assertThat(read.definition().maxRounds()).isEqualTo(10);
        assertThat(read.definition().modelSettings()).isEqualTo(ModelSettings.NONE);
        assertThat(read.definition().metadata()).as("every skill, no memory setting")
                .doesNotContainKeys("skills", "memory", "responseReviewers");
    }

    @Test
    void textThatIsNoAgentIsRefused() {
        assertThatThrownBy(() -> files.read("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.read("{\"description\": \"nameless\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.read("not json")).isInstanceOf(IllegalArgumentException.class);
    }
}
