package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.domain.TraceContext;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.tools.todo.TodoList;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A repository is bound to one namespace: what is written through the one
 * bound to A is invisible — to find, list and delete — through the one bound
 * to B on the same tables, and A still finds it afterwards.
 */
class PgNamespaceIsolationTest {

    private static final Namespace A = new Namespace("team-a");
    private static final Namespace B = new Namespace("team-b");
    private static final UserId DAVID = UserId.of("david");

    private Sql sql;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_agent_definition", "mc_agent_session", "mc_conversation_summary",
                "mc_working_memory", "mc_todo_list", "mc_llm_call_trace");
    }

    @Test
    void agentDefinitions() {
        var a = new PgAgentDefinitionRepository(sql, A).initSchema();
        var b = new PgAgentDefinitionRepository(sql, B).initSchema();
        AgentDefinition d = a.save(AgentDefinition.create("web-researcher", "desc", "prompt", "hello", "agent-default"));

        assertThat(b.findById(d.id())).isEmpty();
        assertThat(b.findByName("web-researcher")).isEmpty();
        assertThat(b.findAll()).isEmpty();
        b.deleteById(d.id());
        assertThat(a.findById(d.id())).contains(d);
        assertThat(a.findByName("web-researcher")).contains(d);
    }

    @Test
    void agentSessions() {
        var a = new PgAgentSessionRepository(sql, A).initSchema();
        var b = new PgAgentSessionRepository(sql, B).initSchema();
        AgentSession parent = AgentSession.start(AgentId.random(), DAVID, ConversationId.random());
        AgentSession child = new AgentSession(SessionId.random(), parent.agentDefinitionId(), DAVID,
                ConversationId.random(), "t", SessionStatus.ACTIVE, Instant.now(), null, parent.id(), null, null);
        a.create(parent);
        a.create(child);

        assertThat(b.findById(parent.id())).isEmpty();
        assertThat(b.findByUser(DAVID)).isEmpty();
        assertThat(b.findHeadersByUser(DAVID)).isEmpty();
        assertThat(b.findByAgent(parent.agentDefinitionId(), DAVID)).isEmpty();
        assertThat(b.findByParentSession(parent.id())).isEmpty();
        b.deleteById(parent.id());
        assertThat(a.findById(parent.id())).contains(parent);
        assertThat(a.findByParentSession(parent.id())).containsExactly(child);
    }

    @Test
    void conversationSummaries() {
        var a = new PgConversationSummaryRepository(sql, A).initSchema();
        var b = new PgConversationSummaryRepository(sql, B).initSchema();
        ConversationId conversation = ConversationId.random();
        ConversationSummary s = ConversationSummary.create(conversation, 1, 10, 10, "gist");
        a.save(s);

        assertThat(b.findByConversation(conversation)).isEmpty();
        b.deleteByConversation(conversation);
        assertThat(a.findByConversation(conversation)).containsExactly(s);
    }

    @Test
    void workingMemory() {
        var a = new PgWorkingMemoryRepository(sql, A).initSchema();
        var b = new PgWorkingMemoryRepository(sql, B).initSchema();
        AuthenticationInfo david = AuthenticationInfo.of(DAVID);
        SessionId session = SessionId.random();
        WorkingMemory memory = new WorkingMemory("prompt", 1, List.of(), 0, "cl100k", 128_000);
        a.save(session, david, memory);
        a.saveSummary(session, david, "gist");

        assertThat(b.findBySession(session, david)).isEmpty();
        assertThat(b.loadSummary(session, david)).isEmpty();
        b.deleteSummary(session, david);
        b.delete(session, david);
        assertThat(a.findBySession(session, david)).contains(memory);
        assertThat(a.loadSummary(session, david)).contains("gist");
    }

    @Test
    void todoLists() {
        var a = new PgTodoListRepository(sql, A).initSchema();
        var b = new PgTodoListRepository(sql, B).initSchema();
        SessionId session = SessionId.random();
        TodoList list = TodoList.empty(session);
        a.save(list);

        assertThat(b.findBySession(session)).isEmpty();
        b.deleteBySession(session);
        assertThat(a.findBySession(session)).contains(list);
    }

    @Test
    void llmCallTraces() {
        var a = new PgLlmCallTraceRepository(sql, 1, A).initSchema();
        var b = new PgLlmCallTraceRepository(sql, 1, B).initSchema();
        ConversationId conversation = ConversationId.random();
        SessionId session = SessionId.random();
        ChatTurnId root = ChatTurnId.random();
        ChatTurnId childTurn = ChatTurnId.random();
        LlmCallTrace child = new LlmCallTrace(TraceId.random(),
                new TraceContext(conversation, session, childTurn, root, 1, "agent"),
                Instant.ofEpochMilli(1_000), 1L, "c", "m", 1, 1, "stop", "{}", List.of(), null, null, null);
        a.save(child);
        // retention of 1 in B must not drop A's trace of the same conversation
        b.save(new LlmCallTrace(TraceId.random(), child.context(), Instant.ofEpochMilli(2_000),
                1L, "c", "m", 1, 1, "stop", "{}", List.of(), null, null, null));

        assertThat(b.findById(child.id())).isEmpty();
        assertThat(b.findByTurn(childTurn)).hasSize(1).doesNotContain(child);
        assertThat(b.findDescendants(root)).hasSize(1).doesNotContain(child);
        b.deleteBySession(session);
        assertThat(b.findByConversation(conversation)).isEmpty();
        assertThat(b.findHeadersByConversation(conversation)).isEmpty();

        assertThat(a.findById(child.id())).contains(child);
        assertThat(a.findBySession(session)).containsExactly(child);
        assertThat(a.findDescendants(root)).containsExactly(child);
        assertThat(a.findHeadersByConversation(conversation)).hasSize(1);
    }
}
