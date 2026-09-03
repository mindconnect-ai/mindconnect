package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.memory.domain.ConversationSummary;
import ai.mindconnect.agent.tools.todo.TodoItem;
import ai.mindconnect.agent.tools.todo.TodoList;
import ai.mindconnect.agent.tools.todo.TodoStatus;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PgTodoAndSummaryRepositoryTest {

    private PgTodoListRepository todos;
    private PgConversationSummaryRepository summaries;

    @BeforeEach
    void setUp() {
        Sql sql = TestDb.fresh("mc_todo_list", "mc_conversation_summary");
        todos = new PgTodoListRepository(sql).initSchema();
        summaries = new PgConversationSummaryRepository(sql).initSchema();
    }

    @Test
    void aTodoListIsKeyedByItsSessionAndReplacedOnSave() {
        UUID session = UUID.randomUUID();
        TodoList first = new TodoList(session, List.of(
                new TodoItem(UUID.randomUUID(), "write tests", "writing tests", TodoStatus.IN_PROGRESS, 1)), null);
        todos.save(first);
        assertThat(todos.findBySession(session)).contains(first);

        TodoList cleared = TodoList.empty(session);
        todos.save(cleared);
        assertThat(todos.findBySession(session)).contains(cleared);

        todos.deleteBySession(session);
        assertThat(todos.findBySession(session)).isEmpty();
        assertThat(todos.findBySession(UUID.randomUUID())).isEmpty();
    }

    @Test
    void summariesComeBackInSequenceOrderAndGoTogether() {
        UUID conversation = UUID.randomUUID();
        ConversationSummary later = ConversationSummary.create(conversation, 11, 20, 10, "second");
        ConversationSummary earlier = ConversationSummary.create(conversation, 1, 10, 10, "first");
        summaries.save(later);
        summaries.save(earlier);
        summaries.save(ConversationSummary.create(UUID.randomUUID(), 1, 5, 5, "elsewhere"));

        assertThat(summaries.findByConversationId(conversation)).containsExactly(earlier, later);
        summaries.deleteByConversationId(conversation);
        assertThat(summaries.findByConversationId(conversation)).isEmpty();
    }
}
