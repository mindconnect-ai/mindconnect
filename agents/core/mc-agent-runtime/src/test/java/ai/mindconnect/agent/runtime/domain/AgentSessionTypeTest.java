package ai.mindconnect.agent.runtime.domain;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.agent.runtime.service.stream.UserEvent;
import ai.mindconnect.message.domain.ConversationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The type of a session: a chat unless said otherwise, carried by every copy,
 * in the JSON — and what keeps a feature's sessions out of the chat's list.
 */
class AgentSessionTypeTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final UserId ME = UserId.of("u");

    private static AgentSession session() {
        return AgentSession.start(AgentId.random(), ME, ConversationId.random());
    }

    @Test
    void aSessionIsAChatUnlessToldOtherwise() {
        assertThat(session().type()).isEqualTo(AgentSession.CHAT);
        assertThat(session().withType(null).type()).isEqualTo(AgentSession.CHAT);
        assertThat(session().withType("office").isOfType("office")).isTrue();
        assertThat(session().isOfType(null)).as("no type asks for a chat").isTrue();
    }

    @Test
    void everyCopyKeepsTheType() {
        AgentSession office = session().withType("office");
        assertThat(List.of(
                office.withTitle("t"), office.withApprovedTool("bash"),
                office.withActivatedTools(List.of("web_search")), office.withWorkingDir("/tmp"),
                office.withAdditionalDirs(List.of("/data")), office.complete(), office.error()))
                .extracting(AgentSession::type).containsOnly("office");
    }

    @Test
    void aTypeIsAPlainWord() {
        assertThatThrownBy(() -> session().withType("Office Chat"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session().withType("../x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(session().withType("office.draft-2").type()).isEqualTo("office.draft-2");
    }

    @Test
    void theTypeIsInTheJson_andAnOldSessionReadsAsAChat() throws Exception {
        AgentSession office = session().withType("office");
        assertThat(JSON.readValue(JSON.writeValueAsString(office), AgentSession.class)).isEqualTo(office);

        String old = """
                {"id":"%s","agentDefinitionId":"%s","userId":"u","conversationId":"%s",
                 "status":"ACTIVE","startedAt":"2026-09-01T00:00:00Z"}
                """.formatted(SessionId.random().value(), AgentId.random().value(),
                ConversationId.random().value());
        assertThat(JSON.readValue(old, AgentSession.class).type()).isEqualTo(AgentSession.CHAT);
    }

    @Test
    void theChatsListLeavesOtherTypesOut() {
        InMemoryAgentSessionRepository repo = new InMemoryAgentSessionRepository();
        AgentSession chat = repo.create(new AgentSession(SessionId.random(), AgentId.random(), ME,
                ConversationId.random(), null, SessionStatus.ACTIVE, Instant.parse("2026-09-01T00:00:00Z"),
                null, null, null, null));
        AgentSession office = repo.create(session().withType("office"));

        assertThat(repo.findByUser(ME)).containsExactlyInAnyOrder(chat, office);
        assertThat(repo.findByUser(ME, AgentSession.CHAT)).containsExactly(chat);
        assertThat(repo.findByUser(ME, "office")).containsExactly(office);
        assertThat(repo.findHeadersByUser(ME, null)).extracting(AgentSessionHeader::id)
                .containsExactly(chat.id());
    }

    @Test
    void theStartEventSaysWhatKindOfSessionStarted() {
        assertThat(new UserEvent.SessionStarted(SessionId.random(), AgentId.random()).sessionType())
                .isEqualTo(AgentSession.CHAT);
        assertThat(new UserEvent.SessionStarted(SessionId.random(), AgentId.random(), "office").sessionType())
                .isEqualTo("office");
    }
}
