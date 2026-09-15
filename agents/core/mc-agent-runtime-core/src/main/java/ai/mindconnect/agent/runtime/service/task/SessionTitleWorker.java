package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.StatelessAgentSeeder;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.service.stream.UserEvent;
import ai.mindconnect.message.domain.ChatTurn;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.TaskWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Names a chat after its first exchange — as a task of its own, behind the
 * turn that produced the exchange. The turn worker submits it as a child
 * once a root turn has answered an untitled session; the queue stamps it
 * with the turn's scope, keeps it across a restart, retries it, and shows
 * it in the task monitor like every other piece of work.
 *
 * <p>Idempotent: a session that has a title by the time this runs — the
 * user named it while the model was thinking — keeps it, and the
 * generator is not asked. A generator that fails or answers nothing leaves
 * the user's first message as the title, so no chat stays nameless.
 */
public final class SessionTitleWorker implements TaskWorker {

    public static final String TYPE = "agent.title";
    public static final String SESSION_ID = "sessionId";
    public static final String TURN_ID = "turnId";

    private static final Logger log = LoggerFactory.getLogger(SessionTitleWorker.class);

    private final AgentSessionService sessionService;
    private final ConversationManager conversationManager;
    private final AgentTaskRunner agentTaskRunner;
    private final UserChannels userChannels;

    public SessionTitleWorker(AgentSessionService sessionService, ConversationManager conversationManager,
                              AgentTaskRunner agentTaskRunner, UserChannels userChannels) {
        this.sessionService = sessionService;
        this.conversationManager = conversationManager;
        this.agentTaskRunner = agentTaskRunner;
        this.userChannels = userChannels;
    }

    /** One title task per session: a second submission for the same session is the same task. */
    public static String taskIdFor(SessionId sessionId) {
        return "task_title_" + sessionId.value();
    }

    /** Submitted by the turn worker as a child of the turn; a step behind fresh turns in priority. */
    public static TaskSubmission submission(SessionId sessionId, ChatTurnId turnId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(SESSION_ID, sessionId.value());
        payload.put(TURN_ID, turnId.value());
        return TaskSubmission.of(TYPE, payload).withId(taskIdFor(sessionId)).withPriority(2);
    }

    @Override
    public TaskOutcome execute(TaskContext ctx) {
        SessionId sessionId = SessionId.of(String.valueOf(ctx.task().payload().get(SESSION_ID)));
        AgentSession session = sessionService.findSession(sessionId);
        if (session.title() != null) {
            return TaskOutcome.done("kept");
        }
        Optional<ChatTurn> exchange = firstExchange(session, ctx.task().payload().get(TURN_ID));
        if (exchange.isEmpty()) {
            return TaskOutcome.done("nothing to name");
        }
        String userMessage = text(exchange.get().userMessage());
        String answer = exchange.get().assistantAnswer().map(SessionTitleWorker::text).orElse("");
        String title;
        try {
            String generated = agentTaskRunner.run(StatelessAgentSeeder.TITLE_GENERATOR,
                    "User: " + userMessage + "\nAgent: " + answer);
            title = generated == null || generated.isBlank() ? userMessage : generated.strip();
        } catch (Exception e) {
            log.warn("Title generation failed for session {} — using the user's message: {}",
                    sessionId.value(), e.getMessage());
            title = userMessage;
        }
        if (title.isBlank()) {
            return TaskOutcome.done("nothing to name");
        }
        // Only an untitled session takes the generated title: a name the user
        // gave the chat while the title was being generated stays.
        AgentSession titled = sessionService.titleIfUntitled(sessionId, title);
        userChannels.publish(session.userId(), new UserEvent.SessionTitled(sessionId, titled.title()));
        return TaskOutcome.done(titled.title());
    }

    /** The turn named in the payload, else the conversation's first one. */
    private Optional<ChatTurn> firstExchange(AgentSession session, Object turnId) {
        ConversationHistory history = conversationManager.loadCompleteHistory(session.conversationId());
        if (turnId != null) {
            Optional<ChatTurn> named = history.turns().stream()
                    .filter(turn -> turn.turnId() != null && turn.turnId().value().equals(turnId.toString()))
                    .findFirst();
            if (named.isPresent()) return named;
        }
        return history.turns().stream().findFirst();
    }

    private static String text(Message message) {
        if (message == null) return "";
        String content = ai.mindconnect.message.domain.ContentPart.textOf(message.partsOrText());
        return content == null ? "" : content.strip();
    }
}
