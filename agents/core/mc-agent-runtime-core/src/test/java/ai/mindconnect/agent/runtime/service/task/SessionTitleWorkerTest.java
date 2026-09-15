package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskSubmission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTitleWorkerTest {

    private final ConversationId conversationId = ConversationId.random();
    private final List<Message> messages = new ArrayList<>();
    private final Map<SessionId, AgentSession> sessions = new ConcurrentHashMap<>();
    private final AgentSessionService sessionService =
            new AgentSessionService(null, new MapSessions(sessions), conversations(), null, null, null, null, null);
    private final List<String> generatorInputs = new ArrayList<>();

    private SessionTitleWorker worker(AgentTaskRunner generator) {
        return new SessionTitleWorker(sessionService, conversations(), generator, new UserChannels());
    }

    private AgentSession exchange(String question, String answer) {
        AgentSession session = AgentSession.start(AgentId.random(), UserId.of("david"), conversationId);
        sessions.put(session.id(), session);
        messages.add(Message.of(conversationId, "david", ParticipantType.USER, MessageType.CHAT, question, 1));
        messages.add(Message.of(conversationId, session.agentDefinitionId().value(), ParticipantType.AGENT,
                MessageType.CHAT, answer, 2));
        return session;
    }

    @Test
    void namesAnUntitledChatAfterItsFirstExchange() {
        AgentSession session = exchange("What is the weather in Berlin?", "Sunny, 24 °C.");
        AgentTaskRunner generator = (task, input) -> {
            generatorInputs.add(task + "|" + input);
            return " Berlin weather ";
        };

        TaskOutcome outcome = worker(generator).execute(contextOf(session));

        assertThat(outcome).isEqualTo(TaskOutcome.done("Berlin weather"));
        assertThat(sessions.get(session.id()).title()).isEqualTo("Berlin weather");
        assertThat(generatorInputs).containsExactly(
                "title-generator|User: What is the weather in Berlin?\nAgent: Sunny, 24 °C.");
    }

    @Test
    void aTitleTheUserGaveMeanwhileStaysAndTheGeneratorIsNotAsked() {
        AgentSession session = exchange("hi", "hello");
        sessions.put(session.id(), sessions.get(session.id()).withTitle("Weekly report"));

        TaskOutcome outcome = worker((task, input) -> { throw new AssertionError("not asked"); })
                .execute(contextOf(session));

        assertThat(outcome).isEqualTo(TaskOutcome.done("kept"));
        assertThat(sessions.get(session.id()).title()).isEqualTo("Weekly report");
    }

    @Test
    void aFailingGeneratorLeavesTheUsersMessageAsTheTitle() {
        AgentSession session = exchange("Plan my trip", "Sure.");

        TaskOutcome outcome = worker((task, input) -> { throw new IllegalStateException("LLM down"); })
                .execute(contextOf(session));

        assertThat(outcome).isEqualTo(TaskOutcome.done("Plan my trip"));
        assertThat(sessions.get(session.id()).title()).isEqualTo("Plan my trip");
    }

    @Test
    void aChatWithoutAnExchangeIsLeftAlone() {
        AgentSession session = AgentSession.start(AgentId.random(), UserId.of("david"), conversationId);
        sessions.put(session.id(), session);

        TaskOutcome outcome = worker((task, input) -> "x").execute(contextOf(session));

        assertThat(outcome).isEqualTo(TaskOutcome.done("nothing to name"));
        assertThat(sessions.get(session.id()).title()).isNull();
    }

    @Test
    void oneTitleTaskPerSession() {
        SessionId id = SessionId.random();
        TaskSubmission first = SessionTitleWorker.submission(id, ChatTurnId.random());
        TaskSubmission second = SessionTitleWorker.submission(id, ChatTurnId.random());

        assertThat(first.id()).isEqualTo(second.id()).isEqualTo("task_title_" + id.value());
        assertThat(first.type()).isEqualTo(SessionTitleWorker.TYPE);
        assertThat(first.priority()).isEqualTo(2);
    }

    private ConversationManager conversations() {
        return (ConversationManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConversationManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("loadCompleteHistory")) {
                        return ConversationHistory.of(conversationId, List.copyOf(messages));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static TaskContext contextOf(AgentSession session) {
        TaskRecord record = TaskRecord.queued("t1",
                SessionTitleWorker.submission(session.id(), ChatTurnId.random()));
        return new TaskContext() {
            @Override public TaskRecord task() { return record; }
            @Override public boolean cancelRequested() { return false; }
            @Override public void onCancel(Runnable hook) { }
            @Override public Map<String, Object> state() { return Map.of(); }
            @Override public void updateState(Map<String, Object> state) { }
            @Override public String submitChild(TaskSubmission s) { throw new UnsupportedOperationException(); }
            @Override public String submitChild(String type, Map<String, Object> payload) { throw new UnsupportedOperationException(); }
            @Override public List<TaskRecord> children() { return List.of(); }
            @Override public List<TaskNotification> notifications() { return List.of(); }
            @Override public boolean notifyTask(String taskId, Map<String, Object> payload) { return false; }
            @Override public boolean notifyParent(Map<String, Object> payload) { return false; }
        };
    }

    private record MapSessions(Map<SessionId, AgentSession> byId) implements AgentSessionRepository {
        @Override public AgentSession create(AgentSession s) { byId.put(s.id(), s); return s; }
        @Override public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
            return Optional.ofNullable(byId.computeIfPresent(id, (k, s) -> change.apply(s)));
        }
        @Override public Optional<AgentSession> findById(SessionId id) { return Optional.ofNullable(byId.get(id)); }
        @Override public List<AgentSession> findByAgent(AgentId agent, UserId user) { return List.of(); }
        @Override public List<AgentSession> findByUser(UserId u) { return List.of(); }
        @Override public List<AgentSession> findByParentSession(SessionId parent) { return List.of(); }
        @Override public void deleteById(SessionId id) { byId.remove(id); }
    }
}
