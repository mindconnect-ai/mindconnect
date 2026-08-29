package ai.mindconnect.agent.domain.session;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.common.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The session agent's contract: its id, its invariant, and its JSON. */
class SessionAgentTest {

    private static final Namespace NS = new Namespace("local");
    // Same module set the repositories use — Instant needs jsr310.
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private static AgentSession session() {
        return AgentSession.start(UUID.randomUUID(), NS, "alice", UUID.randomUUID());
    }

    @Test
    void inlineAgentStampsItsIdIntoEveryTool() {
        var agent = InlineSessionAgent.of("Chat", "be helpful", "gpt",
                List.of("workspace_read", "todo_write"), true);

        // The id these tools carry is what SpiToolRegistry turns into a
        // ToolCallScope, and the AGENT_USER workspace is keyed by it. Null
        // here means the agent-scoped workspace throws at call time.
        assertThat(agent.tools()).hasSize(2)
                .allSatisfy(tool -> assertThat(tool.agentDefinitionId()).isEqualTo(agent.id()));
        assertThat(agent.tools()).extracting(t -> t.name())
                .containsExactly("workspace_read", "todo_write");
    }

    @Test
    void toolSearchIsOffWhenNotAskedFor() {
        assertThat(InlineSessionAgent.of("Chat", "p", "gpt", List.of(), true).toolSearch())
                .isEqualTo(new AgentDefinition.ToolSearchConfig(true, List.of("*")));
        assertThat(InlineSessionAgent.of("Chat", "p", "gpt", List.of(), false).toolSearch())
                .isEqualTo(AgentDefinition.ToolSearchConfig.OFF);
    }

    @Test
    void aSessionNeedsExactlyOneMainAgent() {
        var main = InlineSessionAgent.of("Chat", "p", "gpt", List.of(), true);
        var notMain = new InlineSessionAgent(main.id(), false, "Chat", "p", "gpt",
                main.tools(), main.toolSearch());

        assertThat(session().withSessionAgents(List.of(main)).mainAgent()).contains(main);

        // Without a main agent the runtime would fall back to agentDefinitionId,
        // which for an inline agent resolves to nothing — fail loudly instead.
        assertThatThrownBy(() -> session().withSessionAgents(List.of(notMain)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one main agent");
        assertThatThrownBy(() -> session().withSessionAgents(List.of(main, main)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("found 2");
    }

    @Test
    void emptyIsAllowed_thatIsWhatEverySessionWrittenBeforeThisLooksLike() {
        assertThat(session().sessionAgents()).isEmpty();
        assertThat(session().mainAgent()).isEmpty();
    }

    @Test
    void inlineAndRefSurviveJsonRoundTrip() throws Exception {
        var inline = InlineSessionAgent.of("Chat", "be helpful", "gpt", List.of("todo_read"), true);
        var ref = new SessionAgentRef(UUID.randomUUID(), true, "Poet", "claude", null, null);

        for (SessionAgent agent : List.of(inline, ref)) {
            String json = JSON.writeValueAsString(agent);
            assertThat(json).contains("\"kind\":\"" + (agent instanceof InlineSessionAgent ? "inline" : "ref") + "\"");
            assertThat(JSON.readValue(json, SessionAgent.class)).isEqualTo(agent);
        }
    }

    @Test
    void aSessionWithoutTheFieldStillDeserialises() throws Exception {
        // Every session.json written before session agents existed.
        String legacy = """
                {"id":"%s","agentDefinitionId":"%s","namespace":{"value":"local"},
                 "userId":"alice","conversationId":"%s","status":"ACTIVE",
                 "startedAt":"2026-08-29T10:00:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        AgentSession restored = JSON.readValue(legacy, AgentSession.class);
        assertThat(restored.sessionAgents()).isEmpty();
        assertThat(restored.mainAgent()).isEmpty();
    }
}
