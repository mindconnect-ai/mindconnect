package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import ai.mindconnect.agent.chat.stream.SessionFrame;
import ai.mindconnect.agent.chat.stream.SessionFrameChannels;
import ai.mindconnect.agent.chat.stream.StreamAttachment;
import ai.mindconnect.agent.chat.stream.TurnFrame;
import ai.mindconnect.agent.chat.stream.UserChannels;
import ai.mindconnect.agent.chat.stream.UserEvent;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.message.domain.ChatTurnId;

/**
 * The two streams a client reconnects to — a session's and its user's — fed
 * from the turns of the runtime on the plugin kernel. Both are buffered, so
 * a client that attaches is replayed what it missed.
 */
final class ChatStreams {

    private final SessionFrameChannels sessions;
    private final UserChannels users;

    ChatStreams(SessionFrameChannels sessions, UserChannels users) {
        this.sessions = sessions;
        this.users = users;
    }

    /** Where a turn's frames go to reach the session's stream; that the turn began is told to the user's. */
    Consumer<TurnFrame> started(AgentSession chat, ChatTurnId turnId) {
        users.publish(chat.userId(), new UserEvent.TurnStarted(chat.id(), turnId));
        return sessions.publisherFor(chat.id().value(), turnId.value());
    }

    /** The turn has to ask: the user's stream names each tool call that waits. */
    void asked(AgentSession chat, List<Interruption> interruptions) {
        interruptions.stream()
                .filter(interruption -> Interruption.TOOL_APPROVAL.equals(interruption.kind()))
                .forEach(interruption -> users.publish(chat.userId(), new UserEvent.ApprovalRequested(chat.id(),
                        interruption.call().callId(), interruption.call().toolName())));
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
    StreamAttachment attach(AgentSession chat, long afterSeq, String liveTurnId,
                            Consumer<Channel.Event<SessionFrame>> consumer) {
        String sessionId = chat.id().value();
        long firstBuffered = sessions.earliestBufferedSeq(sessionId);
        long latest = sessions.lastSeq(sessionId);
        return new StreamAttachment(sessions.subscribe(sessionId, afterSeq, consumer), liveTurnId, firstBuffered,
                latest);
    }
}
