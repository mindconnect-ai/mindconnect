package ai.mindconnect.agent.migration.earlier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.jackson.IdJsonModule;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.adapter.FileAgentRepository;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.memory.SessionSummaryStore;
import ai.mindconnect.agent.runtime.store.DefaultSessionStore;
import ai.mindconnect.agent.runtime.store.FileSessionMessageRepository;
import ai.mindconnect.agent.runtime.store.FileSessionRepository;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jackson.JsonMapperFactory;

/**
 * A data directory the earlier runtime wrote, taken over on files: an agent,
 * a session that talked with it, one under it, and one whose agent is gone.
 */
class EarlierRuntimeMigrationTest {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("local"));
    private static final JsonMapperFactory MAPPERS = new JsonMapperFactory(List.of(new IdJsonModule()));
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path data;

    private final ObjectMapper json = MAPPERS.create().registerModule(new JavaTimeModule());
    private FileSessionRepository sessions;
    private FileSessionMessageRepository target;
    private EarlierRuntimeMigration migration;

    @BeforeEach
    void an_earlier_runtimes_namespace() throws IOException {
        Path local = data.resolve("local");
        write(local.resolve("system/agents/a1.json"), """
                {"id": "a1", "name": "Owl", "description": "Knows owls", "systemPrompt": "You know owls.",
                 "llmConfigName": "small", "maxIterations": 7, "tools": [{"name": "bash", "enabled": true}],
                 "status": "active", "createdAt": 1.7356896E9, "updatedAt": "2026-09-02T10:00:00Z"}
                """);
        write(local.resolve("sessions/s1/session.json"), """
                {"id": "s1", "userId": "ada", "agentDefinitionId": "a1", "conversationId": "c1",
                 "title": "Owls", "type": "chat", "workingDir": "/work/owls", "additionalDirs": ["/work/mice"],
                 "approvedTools": ["bash"], "activatedTools": ["read_pdf"],
                 "attachedFiles": [{"id": "f1", "name": "owl.png", "path": "/up/owl.png",
                                    "mediaType": "image/png", "sizeBytes": 12}],
                 "sessionAgents": [], "startedAt": "2026-09-03T10:00:00Z", "status": "ACTIVE"}
                """);
        write(local.resolve("sessions/s2/session.json"), """
                {"id": "s2", "userId": "ada", "agentDefinitionId": "a1", "parentSessionId": "s1",
                 "conversationId": "c2", "startedAt": 1788433200.25}
                """);
        write(local.resolve("sessions/s3/session.json"), """
                {"id": "s3", "userId": "bob", "agentDefinitionId": "gone", "conversationId": "c3",
                 "type": "office", "startedAt": "2026-09-04T10:00:00Z"}
                """);
        write(local.resolve("conversations/c1/summaries/0000000001_x.json"), """
                {"id": "x", "conversationId": "c1", "content": "They talked about owls.",
                 "fromSequenceNum": 1, "toSequenceNum": 2, "messageCount": 2}
                """);
        // The conversations as the message repository left them: a file per message, named by its place.
        message(local, "c1", 1, "m1", "CHAT", "USER", "Tell me about owls", "t1");
        message(local, "c1", 2, "m2", "TOOL_CALL", "AGENT",
                "{\\\"toolCalls\\\":[{\\\"id\\\":\\\"call_1\\\",\\\"name\\\":\\\"search\\\",\\\"arguments\\\":{\\\"q\\\":\\\"owls\\\"}}]}", "t1");
        message(local, "c1", 3, "m3", "TOOL_RESULT", "AGENT",
                "{\\\"toolCallId\\\":\\\"call_1\\\",\\\"toolName\\\":\\\"search\\\",\\\"result\\\":\\\"They hunt at night.\\\"}", "t1");
        message(local, "c1", 4, "m4", "CHAT", "AGENT", "They hunt at night.", "t1");
        message(local, "c1", 5, "m5", "APPROVAL_REQUEST", "AGENT", "", "t1");
        // A sub-agent's session had a conversation of its own, under an id that is not the session's.
        message(local, "c2", 1, "m6", "CHAT", "AGENT", "Mice hide.", "t1");

        sessions = new FileSessionRepository(data, json, SCOPE);
        target = new FileSessionMessageRepository(data, json, SCOPE, NOW);
        migration = new EarlierRuntimeMigration(new FileEarlierRuntimeData(data, json, SCOPE),
                new FileAgentRepository(data, json, SCOPE), sessions, target, new AgentJson(json), json, NOW);
    }

    private static void message(Path namespace, String conversation, int place, String id, String type,
                                String sender, String content, String turn) throws IOException {
        write(namespace.resolve("conversations/" + conversation + "/messages/" + String.format("%010d", place)
                + "_" + id + ".json"), """
                {"id": "%s", "conversationId": "%s", "senderId": "x", "senderType": "%s", "type": "%s",
                 "content": "%s", "metadata": {}, "sequenceNum": %d, "sentAt": "2026-09-03T10:00:0%dZ",
                 "compressed": false, "turnId": "%s"}
                """.formatted(id, conversation, sender, type, content, place, place, turn));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void agents_and_sessions_are_taken_over_with_their_ids_and_the_sessions_read_their_conversations() {
        String report = migration.migrate();

        assertThat(report).isEqualTo("agents: 1 taken over, 0 there before; "
                + "sessions: 3 taken over, 0 there before, 1 with a summary");
        var agent = new FileAgentRepository(data, json, SCOPE).find(AgentId.of("a1")).orElseThrow();
        assertThat(agent.definition().instructions()).isEqualTo("You know owls.");

        Session owls = sessions.find(SessionId.of("s1")).orElseThrow();
        assertThat(owls.userId()).isEqualTo(UserId.of("ada"));
        assertThat(owls.createdAt()).isEqualTo(Instant.parse("2026-09-03T10:00:00Z"));
        assertThat(owls.agent().name()).isEqualTo("Owl");
        assertThat(owls.metadata())
                .containsEntry(ManagedSession.TITLE, "Owls")
                .containsEntry(ManagedSession.TYPE, "chat")
                .containsEntry(ManagedSession.WORKING_DIRECTORY, "/work/owls")
                .containsEntry(ManagedSession.ADDITIONAL_DIRECTORIES, List.of("/work/mice"))
                .containsEntry("approvedTools", List.of("bash"))
                .containsEntry("activatedTools", List.of("read_pdf"))
                .containsEntry(ManagedSession.AGENT, ManagedSession.REF)
                .containsEntry(ManagedSession.AGENT_ID, "a1")
                .containsEntry(EarlierRuntimeMigration.MIGRATED_AT, "2026-11-25T12:00:00Z");
        assertThat((List<?>) owls.metadata().get(ManagedSession.ATTACHMENTS)).singleElement()
                .isEqualTo(Map.of("id", "f1", "name", "owl.png", "path", "/up/owl.png", "mediaType", "image/png",
                        "sizeBytes", 12));
        SessionStore moved = new DefaultSessionStore(sessions, target);
        assertThat(moved.history(SessionId.of("s1"))).extracting(stored -> stored.message().type()).containsExactly(
                TurnMessage.Type.USER, TurnMessage.Type.TOOL_CALL, TurnMessage.Type.TOOL_RESULT,
                TurnMessage.Type.ASSISTANT);
        assertThat(moved.history(SessionId.of("s1"))).extracting(StoredMessage::sequence)
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(moved.history(SessionId.of("s1")).get(0).storedAt()).isEqualTo(Instant.parse("2026-09-03T10:00:01Z"));
        TurnMessage call = moved.messages(SessionId.of("s1")).get(1);
        assertThat(call.id()).isEqualTo("m2");
        assertThat(call.toolCalls()).singleElement()
                .isEqualTo(new TurnMessage.ToolCall("call_1", "search", Map.of("q", "owls")));
        TurnMessage result = moved.messages(SessionId.of("s1")).get(2);
        assertThat(result.text()).isEqualTo("They hunt at night.");
        assertThat(result.metadata()).containsEntry("callId", "call_1").containsEntry("toolName", "search");
        String second = call.id();
        assertThat(new SessionSummaryStore(() -> moved).find(SessionId.of("s1")).orElseThrow()
                .throughMessageId()).isEqualTo(second);

        assertThat(agent.createdAt()).isEqualTo(Instant.ofEpochSecond(1735689600));
        assertThat(sessions.find(SessionId.of("s2")).orElseThrow().parentId()).isEqualTo(SessionId.of("s1"));
        assertThat(sessions.find(SessionId.of("s2")).orElseThrow().createdAt())
                .isEqualTo(Instant.ofEpochSecond(1788433200, 250_000_000));
        assertThat(moved.messages(SessionId.of("s2"))).extracting(TurnMessage::text).containsExactly("Mice hide.");
        Session orphan = sessions.find(SessionId.of("s3")).orElseThrow();
        assertThat(orphan.agent()).isNull();
        assertThat(orphan.metadata()).containsEntry(ManagedSession.TYPE, "office")
                .doesNotContainKey(ManagedSession.AGENT_ID);
    }

    @Test
    void a_second_run_finds_everything_there() {
        migration.migrate();

        assertThat(migration.migrate()).isEqualTo("agents: 0 taken over, 1 there before; "
                + "sessions: 0 taken over, 3 there before, 0 with a summary");
    }

    @Test
    void a_session_without_a_user_stops_the_migration_after_the_others_are_taken_over() throws IOException {
        write(data.resolve("local/sessions/s4/session.json"), """
                {"id": "s4", "conversationId": "c4"}
                """);

        assertThatThrownBy(migration::migrate).hasMessageContaining("session s4: no user");

        assertThat(sessions.find(SessionId.of("s1"))).isPresent();
        assertThat(sessions.find(SessionId.of("s4"))).isEmpty();
    }
}
