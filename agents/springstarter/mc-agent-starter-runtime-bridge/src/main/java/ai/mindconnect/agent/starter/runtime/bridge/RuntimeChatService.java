package ai.mindconnect.agent.starter.runtime.bridge;

import java.time.Instant;
import java.util.HashMap;
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
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.domain.TurnStatus;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.message.domain.ChatTurnId;

/**
 * The chat of the application on the runtime on the plugin kernel, behind
 * the calls the chat page already makes: submit a message, cancel, answer
 * an approval. The page keeps its sessions where it always had them; before
 * a turn the session is made known to the runtime, with the agent it has
 * right now and the conversation its messages are in.
 */
public final class RuntimeChatService {

    private final TurnService turns;
    private final SessionService sessionService;
    private final SessionStore sessions;
    private final AgentSessionRepository chatSessions;
    private final SessionAgentResolver agents;
    private final AgentDefinitionMapper mapper;
    private final AgentChatService earlier;

    /**
     * Metadata of the runtime's session: the turn last submitted in it — what
     * a cancel and an approval answer are meant for. Kept in the session and
     * not in this object, so both still find their turn after a restart.
     */
    static final String LAST_TURN = "lastTurn";

    public RuntimeChatService(TurnService turns, SessionService sessionService, SessionStore sessions,
                              AgentSessionRepository chatSessions,
                              SessionAgentResolver agents, AgentDefinitionMapper mapper,
                              AgentChatService earlier) {
        this.turns = turns;
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.chatSessions = chatSessions;
        this.agents = agents;
        this.mapper = mapper;
        this.earlier = earlier;
    }

    public ChatTurnHandle submitChat(SessionId sessionId, String text, Consumer<StreamEvent> events) {
        return submitChat(sessionId, ai.mindconnect.message.domain.ContentPart.text(text), events);
    }

    public ChatTurnHandle submitChat(SessionId sessionId, List<ai.mindconnect.message.domain.ContentPart> parts,
                                     Consumer<StreamEvent> events) {
        AgentSession chat = chatSessions.findById(sessionId).orElseThrow(() ->
                new IllegalArgumentException("There is no session " + sessionId.value()));
        // The agent as it is now, for this turn and as the session's: a definition edited since the
        // session was opened holds, and what works for the session between turns has a model to ask.
        AgentDefinition agent = mapper.toRuntime(agents.resolve(chat));
        Session session = known(chat, agent);
        TurnId turnId = turns.submit(new TurnRequest(session.id(),
                parts.stream().map(RuntimeChatService::part).toList(), agent));
        sessions.update(session.id(), current -> current.withMetadata(LAST_TURN, turnId.value()));
        CompletableFuture<String> answer = new CompletableFuture<>();
        StreamEventTranslator translator = new StreamEventTranslator(turnId, turns, events, answer);
        // What the turn says comes from the turn; that it has to ask comes from its session.
        Subscription asking = sessionService.subscribe(session.id(), false, event -> {
            if (event instanceof SessionEvent.TurnInterrupted interrupted && interrupted.turnId().equals(turnId)) {
                translator.interrupted(interrupted.interruptions());
            }
            // A turn that dies does not say so itself; its session does, and the page must hear an end.
            if (event instanceof SessionEvent.TurnFinished finished && finished.turnId().equals(turnId)) {
                failed(finished.status()).ifPresent(answer::completeExceptionally);
            }
        });
        Subscription following = turns.subscribe(turnId, translator);
        answer.whenComplete((text, failure) -> {
            following.close();
            asking.close();
        });
        return new Handle(turnId, sessionId, answer);
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
        Optional<Turn> underway = underway(sessionId);
        Optional<String> asked = underway.map(Turn::interruptions).orElse(List.of()).stream()
                .filter(interruption -> interruption.call() != null && callId.equals(interruption.call().callId()))
                .map(interruption -> interruption.id())
                .findFirst();
        asked.ifPresent(interruptionId -> turns.resume(underway.orElseThrow().id(), List.of(
                !approved ? InterruptionAnswer.reject(interruptionId, "")
                        : scope == ApprovalScope.SESSION ? InterruptionAnswer.approveAlways(interruptionId)
                        : InterruptionAnswer.approve(interruptionId))));
        return asked.isPresent();
    }

    /**
     * What the model would be sent right now, for the memory page. Still
     * the earlier runtime's view of the conversation: the page is drawn from
     * its types, and both runtimes read the same messages.
     */
    public WorkingMemory memorySnapshot(SessionId sessionId) {
        return earlier.memorySnapshot(sessionId);
    }

    /** The chat's session as the runtime knows it — created on its first turn, its directories kept current. */
    private Session known(AgentSession chat, AgentDefinition agent) {
        ai.mindconnect.agent.runtime.api.domain.SessionId id =
                ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.id().value());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RepositorySessionStore.CONVERSATION_ID, chat.conversationId().value());
        if (chat.workingDir() != null) {
            metadata.put(RegistryToolResolver.WORKING_DIRECTORY, chat.workingDir());
        }
        metadata.put(RegistryToolResolver.ADDITIONAL_DIRECTORIES, chat.additionalDirs());
        if (chat.title() != null && !chat.title().isBlank()) {
            // A chat that has a name is given no other.
            metadata.put(SessionTitlePlugin.TITLE, chat.title());
        }
        if (sessions.find(id).isEmpty()) {
            sessions.create(new Session(id, UserId.of(chat.userId().value()),
                    chat.parentSessionId() == null ? null
                            : ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.parentSessionId().value()),
                    agent, metadata, chat.startedAt() == null ? Instant.now() : chat.startedAt()));
        }
        return sessions.update(id, session -> {
            Session current = session.withAgent(agent);
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                current = current.withMetadata(entry.getKey(), entry.getValue());
            }
            return current;
        });
    }

    private static ContentPart part(ai.mindconnect.message.domain.ContentPart part) {
        return switch (part) {
            case ai.mindconnect.message.domain.ContentPart.Text text -> new ContentPart.Text(text.text());
            case ai.mindconnect.message.domain.ContentPart.Image image ->
                    ContentPart.Image.of(new ContentPart.MediaSource.FileId(image.fileId()));
            case ai.mindconnect.message.domain.ContentPart.File file ->
                    new ContentPart.Document(new ContentPart.MediaSource.FileId(file.fileId()), file.name());
        };
    }

    /** A turn of the runtime as the handle the chat page holds. */
    private final class Handle implements ChatTurnHandle {

        private final TurnId turnId;
        private final SessionId sessionId;
        private final CompletableFuture<String> answer;

        private Handle(TurnId turnId, SessionId sessionId, CompletableFuture<String> answer) {
            this.turnId = turnId;
            this.sessionId = sessionId;
            this.answer = answer;
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
            return answer.thenApply(text -> TurnResult.completed(id(), text));
        }

        @Override
        public boolean cancel() {
            turns.cancel(turnId);
            return true;
        }
    }
}
