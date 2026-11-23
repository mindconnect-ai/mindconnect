package ai.mindconnect.agent.starter.runtime.bridge;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.tools.todo.TodoListRepository;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.port.in.ConversationManager;

/**
 * What the application keeps of a session in the stores of the earlier
 * runtime goes with the session: its working memory, summaries, todo list,
 * open approvals, traces, its conversation record, and the copy of the
 * session the earlier store may still hold — left there, it would be taken
 * over again.
 */
final class EarlierStoresCleanup implements SessionCleanup {

    private final WorkingMemoryRepository workingMemory;
    private final ConversationSummaryRepository summaries;
    private final TodoListRepository todoLists;
    private final ToolApprovalRepository approvals;
    private final LlmCallTraceRepository traces;
    private final ConversationManager conversations;
    private final AgentSessionRepository chatSessions;

    EarlierStoresCleanup(WorkingMemoryRepository workingMemory, ConversationSummaryRepository summaries,
                         TodoListRepository todoLists, ToolApprovalRepository approvals,
                         LlmCallTraceRepository traces, ConversationManager conversations,
                         AgentSessionRepository chatSessions) {
        this.workingMemory = workingMemory;
        this.summaries = summaries;
        this.todoLists = todoLists;
        this.approvals = approvals;
        this.traces = traces;
        this.conversations = conversations;
        this.chatSessions = chatSessions;
    }

    @Override
    public void deleted(ManagedSession session) {
        SessionId id = SessionId.of(session.id().value());
        ConversationId conversation = ConversationId.of(
                session.metadata().get(RepositorySessionStore.CONVERSATION_ID) instanceof String named
                        ? named : session.id().value());
        workingMemory.delete(id, AuthenticationInfo.of(ai.mindconnect.agent.UserId.of(session.userId().value())));
        summaries.deleteByConversation(conversation);
        todoLists.deleteBySession(id);
        approvals.deleteForSession(id);
        traces.deleteBySession(id);
        conversations.deleteConversation(conversation);
        chatSessions.deleteById(id);
    }
}
