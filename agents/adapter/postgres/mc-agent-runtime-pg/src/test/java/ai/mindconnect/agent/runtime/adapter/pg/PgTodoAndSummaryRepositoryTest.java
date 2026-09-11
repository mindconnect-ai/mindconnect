package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.tools.todo.TodoItem;
import ai.mindconnect.agent.runtime.tools.todo.TodoList;
import ai.mindconnect.agent.runtime.tools.todo.TodoStatus;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PgTodoAndSummaryRepositoryTest {

    private static final Namespace NS = new Namespace("test");

    private PgTodoListRepository todos;
    private PgConversationSummaryRepository summaries;

    @BeforeEach
    void setUp() {
        Sql sql = TestDb.fresh("mc_todo_list", "mc_conversation_summary");
        todos = new PgTodoListRepository(sql, NS).initSchema();
        summaries = new PgConversationSummaryRepository(sql, NS).initSchema();
    }

    @Test
    void aTodoListIsKeyedByItsSessionAndReplacedOnSave() {
        SessionId session = SessionId.random();
        TodoList first = new TodoList(session, List.of(
                new TodoItem(UUID.randomUUID(), "write tests", "writing tests", TodoStatus.IN_PROGRESS, 1)), null);
        todos.save(first);
        assertThat(todos.findBySession(session)).contains(first);

        TodoList cleared = TodoList.empty(session);
        todos.save(cleared);
        assertThat(todos.findBySession(session)).contains(cleared);

        todos.deleteBySession(session);
        assertThat(todos.findBySession(session)).isEmpty();
        assertThat(todos.findBySession(SessionId.random())).isEmpty();
    }

    @Test
    void summariesComeBackInSequenceOrderAndGoTogether() {
        ConversationId conversation = ConversationId.random();
        ConversationSummary later = ConversationSummary.create(conversation, 11, 20, 10, "second");
        ConversationSummary earlier = ConversationSummary.create(conversation, 1, 10, 10, "first");
        summaries.save(later);
        summaries.save(earlier);
        summaries.save(ConversationSummary.create(ConversationId.random(), 1, 5, 5, "elsewhere"));

        assertThat(summaries.findByConversation(conversation)).containsExactly(earlier, later);
        summaries.deleteByConversation(conversation);
        assertThat(summaries.findByConversation(conversation)).isEmpty();
    }
}
