package ai.mindconnect.agent.starter.runtime.bridge;

import ai.mindconnect.agent.management.api.SessionManagement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.domain.TurnStatus;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.message.domain.ChatTurnId;

/**
 * The chat of the application on the runtime on the plugin kernel, behind
 * the calls the chat page already makes: submit a message, cancel, answer
 * an approval. A chat's session is the runtime's session — the chat page
 * reads it the earlier way from the same record — so a turn needs only the
 * agent the chat has right now.
 */
public final class RuntimeChatService {

    private final TurnService turns;
    private final SessionService sessionService;
    private final SessionStore sessions;
    private final AgentSessionRepository chatSessions;
    private final SessionManagement management;
    private final AgentChatService earlier;
    private final ChatStreams streams;

    /**
     * Metadata of the runtime's session: the turn last submitted in it — what
     * a cancel and an approval answer are meant for. Kept in the session and
     * not in this object, so both still find their turn after a restart.
     */
    static final String LAST_TURN = "lastTurn";

    public RuntimeChatService(TurnService turns, SessionService sessionService, SessionStore sessions,
                              AgentSessionRepository chatSessions,
                              SessionManagement management,
                              AgentChatService earlier, ChatStreams streams) {
        this.streams = streams;
        this.turns = turns;
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.chatSessions = chatSessions;
        this.management = management;
        this.earlier = earlier;
    }

    public ChatTurnHandle submitChat(SessionId sessionId, String text, Consumer<StreamEvent> events) {
        return submitChat(sessionId, ai.mindconnect.message.domain.ContentPart.text(text), events);
    }

    public ChatTurnHandle submitChat(SessionId sessionId, List<ai.mindconnect.message.domain.ContentPart> parts,
                                     Consumer<StreamEvent> events) {
        AgentSession chat = chatSessions.findById(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        // The agent as it is now, for this turn and as the session's: a stored agent the chat refers to
        // holds as edited since, and what works for the session between turns has a model to ask.
        AgentDefinition agent = management.agentOf(ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId.value()))
                .orElseThrow(() -> new IllegalStateException("Session " + sessionId.value() + " has no agent"));
        Session session = known(chat, agent);
        TurnId turnId = turns.submit(new TurnRequest(session.id(),
                parts.stream().map(RuntimeChatService::part).toList(), agent, Map.of()));
        sessions.update(session.id(), current -> current.withMetadata(LAST_TURN, turnId.value()));
        ChatTurnId chatTurnId = ChatTurnId.of(turnId.value());
        Consumer<StreamEvent> stream = streams.started(chat, chatTurnId);
        Handle handle = following(sessionId, turnId, stream.andThen(events), false);
        handle.result().whenComplete((text, failure) -> streams.finished(chat, chatTurnId, failure));
        return handle;
    }

    /**
     * Listens to a turn from now on.
     *
     * @param untilAsked whether listening ends when the turn has to ask — for a caller that is answered
     *                   with a stream of its own for every answer it gives; else it lasts to the turn's end
     */
    private Handle following(SessionId sessionId, TurnId turnId, Consumer<StreamEvent> events, boolean untilAsked) {
        ChatTurnId chatTurnId = ChatTurnId.of(turnId.value());
        CompletableFuture<String> answer = new CompletableFuture<>();
        CompletableFuture<TurnResult> outcome = new CompletableFuture<>();
        StreamEventTranslator translator = new StreamEventTranslator(turnId, turns, events, answer);
        // What the turn says comes from the turn; that it has to ask comes from its session.
        Subscription asking = sessionService.subscribe(
                ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId.value()), false, event -> {
            if (event instanceof SessionEvent.TurnInterrupted interrupted && interrupted.turnId().equals(turnId)) {
                translator.interrupted(interrupted.interruptions());
                outcome.complete(TurnResult.incomplete(chatTurnId, earlier.openApprovals(sessionId)));
            }
            // A turn that dies does not say so itself; its session does, and the page must hear an end.
            if (event instanceof SessionEvent.TurnFinished finished && finished.turnId().equals(turnId)) {
                failed(finished.status()).ifPresent(answer::completeExceptionally);
            }
        });
        Subscription following = turns.subscribe(turnId, translator);
        answer.whenComplete((text, failure) -> {
            if (failure == null) {
                outcome.complete(TurnResult.completed(chatTurnId, text));
            } else {
                outcome.completeExceptionally(failure);
            }
        });
        (untilAsked ? outcome : answer).whenComplete((reached, failure) -> {
            following.close();
            asking.close();
        });
        return new Handle(turnId, sessionId, answer, outcome);
    }

    /**
     * The session's stream from after {@code afterSeq} on: what was buffered
     * since is replayed, then it goes on live, across turns.
     */
    public AgentChatService.Attachment attach(SessionId sessionId, long afterSeq,
                                              Consumer<Channel.Event<ai.mindconnect.agent.runtime.service.stream.SessionEvent>> consumer) {
        AgentSession chat = chatSessions.findById(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        return streams.attach(chat, afterSeq,
                underway(sessionId).map(turn -> ChatTurnId.of(turn.id().value())).orElse(null), consumer);
    }

    /** The approvals the chat is waiting for, oldest first — its own and those of the sub-agents under it. */
    public List<ToolApproval> openApprovals(SessionId sessionId) {
        return earlier.openApprovals(sessionId);
    }

    /** Whether there was a turn to cancel. */
    public boolean cancelChat(SessionId sessionId) {
        Optional<Turn> underway = underway(sessionId);
        underway.ifPresent(turn -> turns.cancel(turn.id()));
        return underway.isPresent();
    }

    /** The session's last turn, while it has not ended. */
    private Optional<Turn> underway(SessionId sessionId) {
        return sessions.find(ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId.value()))
                .map(session -> session.metadata().get(LAST_TURN))
                .filter(String.class::isInstance)
                .flatMap(turnId -> turns.find(TurnId.of((String) turnId)))
                .filter(turn -> switch (turn.status()) {
                    case COMPLETED, INCOMPLETE, CANCELLED, FAILED -> false;
                    default -> true;
                });
    }

    /** Why a turn that ended without saying so ended; empty for an end the turn reports itself. */
    private static Optional<Throwable> failed(ai.mindconnect.agent.runtime.api.domain.TurnStatus status) {
        return switch (status) {
            case FAILED -> Optional.of(new IllegalStateException("The turn failed"));
            case CANCELLED -> Optional.of(new CancellationException("The turn was cancelled"));
            default -> Optional.empty();
        };
    }

    /**
     * Answers the approval a tool call waits for. The turn never ended, so
     * the stream it was submitted with carries what follows.
     *
     * @return whether the answer reached a waiting call; false for a card that is stale
     */
    public boolean answerApproval(SessionId sessionId, String callId, boolean approved, ApprovalScope scope) {
        return answered(sessionId, callId, approved, scope, null).isPresent();
    }

    /**
     * Allows the call and streams how the turn goes on: to its end, or to
     * the next question it has. Empty for a card that is stale.
     */
    public Optional<ChatTurnHandle> approve(SessionId sessionId, String callId, ApprovalScope scope,
                                            Consumer<StreamEvent> events) {
        return answered(sessionId, callId, true, scope, events);
    }

    /** Refuses the call and streams how the turn goes on, like {@link #approve}. */
    public Optional<ChatTurnHandle> deny(SessionId sessionId, String callId, Consumer<StreamEvent> events) {
        return answered(sessionId, callId, false, ApprovalScope.ONCE, events);
    }

    /** @param events who hears how the turn goes on; {@code null} for nobody beyond who heard it so far */
    private Optional<ChatTurnHandle> answered(SessionId sessionId, String callId, boolean approved,
                                              ApprovalScope scope, Consumer<StreamEvent> events) {
        Optional<Turn> underway = underway(sessionId);
        Optional<String> asked = underway.map(Turn::interruptions).orElse(List.of()).stream()
                .filter(interruption -> interruption.call() != null && callId.equals(interruption.call().callId()))
                .map(interruption -> interruption.id())
                .findFirst();
        if (asked.isEmpty()) {
            return Optional.empty();
        }
        TurnId turnId = underway.orElseThrow().id();
        // Listening before the answer goes in: what the turn does next is not missed.
        Handle handle = events == null
                ? new Handle(turnId, sessionId, new CompletableFuture<>(), new CompletableFuture<>())
                : following(sessionId, turnId, events, true);
        turns.resume(turnId, List.of(
                !approved ? InterruptionAnswer.reject(asked.get(), "")
                        : scope == ApprovalScope.SESSION ? InterruptionAnswer.approveAlways(asked.get())
                        : InterruptionAnswer.approve(asked.get())));
        chatSessions.findById(sessionId).ifPresent(chat -> streams.answered(chat, callId, approved));
        return Optional.of(handle);
    }

    /**
     * What the model would be sent right now, for the memory page. Still
     * the earlier runtime's view of the conversation: the page is drawn from
     * its types, and both runtimes read the same messages.
     */
    public WorkingMemory memorySnapshot(SessionId sessionId) {
        return earlier.memorySnapshot(sessionId);
    }

    /**
     * The chat's session, which is the runtime's: one record both read. It
     * is given the agent the chat has right now.
     */
    private Session known(AgentSession chat, AgentDefinition agent) {
        return sessions.update(ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.id().value()),
                session -> session.withAgent(agent));
    }

    private static ContentPart part(ai.mindconnect.message.domain.ContentPart part) {
        return switch (part) {
            case ai.mindconnect.message.domain.ContentPart.Text text -> new ContentPart.Text(text.text());
            case ai.mindconnect.message.domain.ContentPart.Image image ->
                    new ContentPart.Image(new ContentPart.MediaSource.FileId(image.fileId()),
                            ContentPart.Image.Detail.AUTO, image.name());
            case ai.mindconnect.message.domain.ContentPart.File file ->
                    new ContentPart.Document(new ContentPart.MediaSource.FileId(file.fileId()), file.name());
        };
    }

    /** A turn of the runtime as the handle the chat page holds. */
    private final class Handle implements ChatTurnHandle {

        private final TurnId turnId;
        private final SessionId sessionId;
        private final CompletableFuture<String> answer;
        private final CompletableFuture<TurnResult> outcome;

        private Handle(TurnId turnId, SessionId sessionId, CompletableFuture<String> answer,
                       CompletableFuture<TurnResult> outcome) {
            this.turnId = turnId;
            this.sessionId = sessionId;
            this.answer = answer;
            this.outcome = outcome;
        }

        @Override
        public ChatTurnId id() {
            return ChatTurnId.of(turnId.value());
        }

        @Override
        public SessionId sessionId() {
            return sessionId;
        }

        @Override
        public TurnStatus status() {
            return turns.find(turnId).map(turn -> switch (turn.status()) {
                case COMPLETED -> TurnStatus.COMPLETED;
                case FAILED -> TurnStatus.FAILED;
                case CANCELLED, INCOMPLETE -> TurnStatus.INCOMPLETE;
                default -> TurnStatus.RUNNING;
            }).orElse(TurnStatus.FAILED);
        }

        @Override
        public CompletableFuture<String> result() {
            return answer;
        }

        @Override
        public CompletableFuture<TurnResult> outcome() {
            return outcome;
        }

        @Override
        public boolean cancel() {
            turns.cancel(turnId);
            return true;
        }
    }
}
