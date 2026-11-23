package ai.mindconnect.agent.starter.runtime.bridge;

import ai.mindconnect.agent.chat.stream.SessionFrame;
import ai.mindconnect.agent.chat.stream.StreamAttachment;
import ai.mindconnect.agent.chat.stream.TurnFrame;
import ai.mindconnect.agent.chat.stream.TurnFrameTranslator;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.management.api.SessionManagement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import ai.mindconnect.agent.chat.stream.TurnResult;
import ai.mindconnect.agent.chat.stream.TurnStatus;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.chat.stream.ChatTurnHandle;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.chat.stream.ApprovalScope;
import ai.mindconnect.agent.chat.stream.ToolApproval;
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

    public ChatTurnHandle submitChat(SessionId sessionId, String text, ChatTurnListener listener) {
        return submit(sessionId, List.of(new ContentPart.Text(text)), listener);
    }

    /** Submits a message whose parts are the runtime's own — text, or media a history handed back. */
    public ChatTurnHandle submit(SessionId sessionId, List<ContentPart> parts, ChatTurnListener listener) {
        Started started = start(sessionId, parts);
        CompletableFuture<String> answer = new CompletableFuture<>();
        Map<String, Optional<Turn>> subAgents = new ConcurrentHashMap<>();
        return following(started, answer, event -> {
            if (started.turnId().value().equals(event.requestId())) {
                listener.onEvent(event);
                answerOf(event, started.turnId()).ifPresent(answer::complete);
            } else {
                // The events of a sub-agent's turn arrive on its caller's turn.
                subAgents.computeIfAbsent(event.requestId(), id -> turns.find(TurnId.of(id)))
                        .ifPresent(subAgent -> listener.onSubAgentEvent(subAgent, event));
            }
        }, listener::asked, false);
    }

    /**
     * Submits a message and reports the turn to {@code frames} as the frames a
     * client streams. The session's stream carries them too, like every turn's.
     */
    public ChatTurnHandle submitFrames(SessionId sessionId, List<ai.mindconnect.message.domain.ContentPart> parts,
                                       Consumer<TurnFrame> frames) {
        Started started = start(sessionId, parts.stream().map(RuntimeChatService::part).toList());
        CompletableFuture<String> answer = new CompletableFuture<>();
        TurnFrameTranslator translator = new TurnFrameTranslator(started.turnId(), turns, frames);
        return following(started, answer, event -> {
            translator.onEvent(event);
            answerOf(event, started.turnId()).ifPresent(answer::complete);
        }, translator::interrupted, false);
    }

    /** A turn that was submitted, and the chat it runs in. */
    private record Started(AgentSession chat, TurnId turnId) { }

    private Started start(SessionId sessionId, List<ContentPart> parts) {
        AgentSession chat = chatSessions.findById(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        // The agent as it is now, for this turn and as the session's: a stored agent the chat refers to
        // holds as edited since, and what works for the session between turns has a model to ask.
        AgentDefinition agent = management.agentOf(ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId.value()))
                .orElseThrow(() -> new IllegalStateException("Session " + sessionId.value() + " has no agent"));
        Session session = known(chat, agent);
        TurnId turnId = turns.submit(new TurnRequest(session.id(), parts, agent, Map.of()));
        return new Started(chat, turnId);
    }

    /** The answer of the turn, once the event that ends it has come. */
    private static Optional<String> answerOf(TurnEvent event, TurnId turnId) {
        return event instanceof TurnEvent.TurnEnded ended && turnId.value().equals(ended.requestId())
                && ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS
                ? Optional.of(ended.outcome().text() == null ? "" : ended.outcome().text()) : Optional.empty();
    }

    /**
     * Listens to a turn from now on.
     *
     * @param answer     completed by {@code events} when the turn ends
     * @param untilAsked whether listening ends when the turn has to ask — for a caller that is answered
     *                   with a stream of its own for every answer it gives; else it lasts to the turn's end
     */
    private Handle following(Started started, CompletableFuture<String> answer, TurnEventListener events,
                             Consumer<List<Interruption>> asked, boolean untilAsked) {
        TurnId turnId = started.turnId();
        SessionId sessionId = started.chat().id();
        ChatTurnId chatTurnId = ChatTurnId.of(turnId.value());
        CompletableFuture<TurnResult> outcome = new CompletableFuture<>();
        // What the turn says comes from the turn; that it has to ask comes from its session.
        Subscription asking = sessionService.subscribe(
                ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId.value()), false, event -> {
            if (event instanceof SessionEvent.TurnInterrupted interrupted && interrupted.turnId().equals(turnId)) {
                asked.accept(interrupted.interruptions());
                outcome.complete(TurnResult.incomplete(chatTurnId, earlier.openApprovals(sessionId)));
            }
            // A turn that dies does not say so itself; its session does, and whoever listens must hear an end.
            if (event instanceof SessionEvent.TurnFinished finished && finished.turnId().equals(turnId)) {
                failed(finished.status()).ifPresent(answer::completeExceptionally);
            }
        });
        Subscription following = turns.subscribe(turnId, events);
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
    public StreamAttachment attach(SessionId sessionId, long afterSeq, Consumer<Channel.Event<SessionFrame>> consumer) {
        AgentSession chat = chatSessions.findById(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        return streams.attach(chat.id(), afterSeq, underway(sessionId).map(turn -> turn.id().value()).orElse(null),
                consumer);
    }

    /** The approvals the chat is waiting for, oldest first — its own and those of the sub-agents under it. */
    public List<ToolApproval> openApprovals(SessionId sessionId) {
        return earlier.openApprovals(sessionId);
    }

    /** Deletes the chat with what was said in it, the sessions under it and what the application keeps of them. */
    public void deleteChat(SessionId sessionId) {
        management.delete(ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId.value()));
    }

    /** Whether there was a turn to cancel. */
    public boolean cancelChat(SessionId sessionId) {
        Optional<Turn> underway = underway(sessionId);
        underway.ifPresent(turn -> turns.cancel(turn.id()));
        return underway.isPresent();
    }

    /** The session's turn that has not ended. */
    private Optional<Turn> underway(SessionId sessionId) {
        return turns.runningTurn(ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId.value()));
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
                                            Consumer<TurnFrame> events) {
        return answered(sessionId, callId, true, scope, events);
    }

    /** Refuses the call and streams how the turn goes on, like {@link #approve}. */
    public Optional<ChatTurnHandle> deny(SessionId sessionId, String callId, Consumer<TurnFrame> events) {
        return answered(sessionId, callId, false, ApprovalScope.ONCE, events);
    }

    /** @param events who hears how the turn goes on; {@code null} for nobody beyond who heard it so far */
    private Optional<ChatTurnHandle> answered(SessionId sessionId, String callId, boolean approved,
                                              ApprovalScope scope, Consumer<TurnFrame> events) {
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
        Handle handle;
        if (events == null) {
            handle = new Handle(turnId, sessionId, new CompletableFuture<>(), new CompletableFuture<>());
        } else {
            // The session's stream already hears the turn from whoever submitted it; this is the caller's own.
            CompletableFuture<String> answer = new CompletableFuture<>();
            TurnFrameTranslator translator = new TurnFrameTranslator(turnId, turns, events);
            handle = following(new Started(chatSessions.findById(sessionId).orElseThrow(), turnId),
                    answer, event -> {
                        translator.onEvent(event);
                        answerOf(event, turnId).ifPresent(answer::complete);
                    }, translator::interrupted, true);
        }
        turns.resume(turnId, List.of(
                !approved ? InterruptionAnswer.reject(asked.get(), "")
                        : scope == ApprovalScope.SESSION ? InterruptionAnswer.approveAlways(asked.get())
                        : InterruptionAnswer.approve(asked.get())));
        chatSessions.findById(sessionId)
                .ifPresent(chat -> streams.answered(chat.id(), chat.userId(), callId, approved));
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
