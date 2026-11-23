package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.chat.stream.StreamEvent;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.chat.stream.SessionChannels;
import ai.mindconnect.agent.chat.stream.SessionEvent;
import ai.mindconnect.agent.chat.stream.UserChannels;
import ai.mindconnect.agent.chat.stream.UserEvent;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.message.domain.ChatTurnId;

/**
 * The two streams a client reconnects to — a session's and its user's — fed
 * from the turns of the runtime on the plugin kernel. They are the buffered
 * channels the earlier runtime publishes to, so a client attaches the way it
 * always did and is replayed what it missed.
 */
final class ChatStreams {

    private final SessionChannels sessions;
    private final UserChannels users;

    ChatStreams(SessionChannels sessions, UserChannels users) {
        this.sessions = sessions;
        this.users = users;
    }

    /** Where a turn's events go to reach the session's stream; that the turn began is told to the user's. */
    Consumer<StreamEvent> started(AgentSession chat, ChatTurnId turnId) {
        users.publish(chat.userId(), new UserEvent.TurnStarted(chat.id(), turnId));
        Consumer<StreamEvent> session = sessions.publisherFor(chat.id(), turnId, 1);
        return event -> {
            session.accept(event);
            if (event instanceof StreamEvent.ApprovalRequested asked) {
                users.publish(chat.userId(),
                        new UserEvent.ApprovalRequested(chat.id(), asked.callId(), asked.toolName()));
            }
        };
    }

    /** @param failure why the turn ended; {@code null} for a turn that came to its end */
    void finished(AgentSession chat, ChatTurnId turnId, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        users.publish(chat.userId(), new UserEvent.TurnFinished(chat.id(), turnId,
                cause == null ? UserEvent.TurnOutcome.COMPLETED
                        : cause instanceof CancellationException ? UserEvent.TurnOutcome.CANCELLED
                        : UserEvent.TurnOutcome.FAILED));
    }

    void answered(AgentSession chat, String callId, boolean approved) {
        users.publish(chat.userId(), new UserEvent.ApprovalAnswered(chat.id(), callId, approved));
    }

    /** @param liveTurnId the turn underway in the session; {@code null} when it is idle */
    AgentChatService.Attachment attach(AgentSession chat, long afterSeq, ChatTurnId liveTurnId,
                                       Consumer<Channel.Event<SessionEvent>> consumer) {
        long firstBuffered = sessions.earliestBufferedSeq(chat.id());
        long latest = sessions.lastSeq(chat.id());
        return new AgentChatService.Attachment(sessions.subscribe(chat.id(), afterSeq, consumer), liveTurnId,
                liveTurnId == null ? null : 1, firstBuffered, latest);
    }
}
