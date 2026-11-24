package ai.mindconnect.agent.protocol.runtime;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.TurnEvent;
import ai.mindconnect.agent.loop.domain.TurnOutcome;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.protocol.Conversation;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.StoredFile;
import ai.mindconnect.agent.protocol.api.AgentResponses;
import ai.mindconnect.agent.protocol.api.Conversations;
import ai.mindconnect.agent.protocol.api.Files;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.Sessions;
import ai.mindconnect.agent.protocol.api.SubscribeRequest;
import ai.mindconnect.agent.protocol.api.Subscription;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.TurnListener;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.out.MessageRepository;

/**
 * The agent protocol on the runtime: a session is a managed session on a
 * stored agent, a response is one turn of it — or the stretch of a turn
 * between two questions — and a conversation is the messages of a session.
 *
 * <p>Everything is the caller's: a session, a response or a file of somebody
 * else is answered like one that does not exist. Who the caller is, the
 * host says with every call ({@code caller}).
 *
 * <p>A turn that has to ask before it may run a tool ends the response
 * {@code INCOMPLETE} with the questions; the answer — an
 * {@code ApprovalResponse} as the next input — lets the same turn go on as a
 * new response.
 */
public final class AgentRuntimeBackend {

    private static final PageRequest EVERYTHING = new PageRequest(0, 1000);

    private final AgentRuntime runtime;
    private final Supplier<UserId> caller;
    private final FileStore fileStore;
    private final FileAttacher fileAttacher;
    /** What all views of one backend share: the responses there are, their turns and whose they are. */
    private final Map<String, ResponseAssembler> assemblers;
    private final Map<String, TurnId> turnOf;
    private final Map<String, UserId> owners;

    private AgentRuntimeBackend(AgentRuntime runtime, Supplier<UserId> caller, FileStore fileStore,
                                FileAttacher fileAttacher, Map<String, ResponseAssembler> assemblers,
                                Map<String, TurnId> turnOf, Map<String, UserId> owners) {
        this.runtime = runtime;
        this.caller = caller;
        this.fileStore = fileStore;
        this.fileAttacher = fileAttacher;
        this.assemblers = assemblers;
        this.turnOf = turnOf;
        this.owners = owners;
    }

    /** @param caller who is calling right now; asked with every call */
    public static AgentRuntimeBackend on(AgentRuntime runtime, Supplier<UserId> caller) {
        return new AgentRuntimeBackend(runtime, caller, null, null,
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    /** With file support: where an upload is stored, and how a stored file gets to a session. */
    public AgentRuntimeBackend withFiles(FileStore fileStore, FileAttacher fileAttacher) {
        return new AgentRuntimeBackend(runtime, caller, fileStore, fileAttacher, assemblers, turnOf, owners);
    }

    public UserId currentUser() {
        return caller.get();
    }

    /**
     * The same backend, acting for {@code user} whatever thread calls it — for
     * work that outlives the request it began in, such as a stream.
     */
    public AgentRuntimeBackend actingFor(UserId user) {
        return new AgentRuntimeBackend(runtime, () -> user, fileStore, fileAttacher, assemblers, turnOf, owners);
    }

    // ── The protocol surfaces, by composition ───────────────────────────────

    public Sessions sessions() { return sessionsApi; }
    public AgentResponses responses() { return responsesApi; }
    public Conversations conversations() { return conversationsApi; }
    public Files files() { return filesApi; }

    public Session open(String agentName) { return sessionsApi.open(agentName); }
    public Response create(ResponseRequest request) { return responsesApi.create(request); }

    private SessionManagement sessionManagement() {
        return runtime.service(SessionManagement.class);
    }

    private static ai.mindconnect.agent.runtime.api.domain.SessionId runtimeId(String sessionId) {
        return ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionId);
    }

    private Optional<ManagedSession> ownedSession(String sessionId) {
        UserId user = caller.get();
        try {
            return sessionManagement().find(runtimeId(sessionId))
                    .filter(session -> session.userId() != null && user.value().equals(session.userId().value()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<ResponseAssembler> ownedResponse(String responseId) {
        UserId user = caller.get();
        if (responseId == null || !user.equals(owners.get(responseId))) {
            return Optional.empty();
        }
        return Optional.ofNullable(assemblers.get(responseId));
    }

    private String agentNameOf(ManagedSession session) {
        return sessionManagement().agentOf(session.id()).map(AgentDefinition::name).orElse("unknown");
    }

    /** The conversation the session's messages are kept in. */
    private String conversationOf(ManagedSession session) {
        return runtime.sessions().find(session.id()).map(RepositorySessionStore::conversationOf)
                .map(ConversationId::value).orElse(session.id().value());
    }

    private Session toProtocol(ManagedSession session) {
        return new Session(session.id().value(), conversationOf(session), agentNameOf(session), session.createdAt());
    }

    private final Sessions sessionsApi = new Sessions() {

        @Override
        public Session open(String agentName) {
            ManagedAgent agent = runtime.service(AgentManagement.class).findByName(agentName)
                    .orElseThrow(() -> new RuntimeBackendException("No agent named '" + agentName + "'"));
            return toProtocol(sessionManagement().open(
                    ai.mindconnect.agent.runtime.api.domain.UserId.of(caller.get().value()), null,
                    new SessionAgent.Ref(agent.id()), null, SessionDirectories.NONE));
        }

        @Override
        public Session openOn(String conversationId, String agentName) {
            throw new RuntimeBackendException(
                    "openOn is not supported yet — the runtime binds the conversation "
                            + "when the session is created");
        }

        @Override
        public Optional<Session> get(String sessionId) {
            return ownedSession(sessionId).map(AgentRuntimeBackend.this::toProtocol);
        }
    };

    private final AgentResponses responsesApi = new AgentResponses() {

        @Override
        public Response create(ResponseRequest request) {
            if (!request.clientTools().isEmpty()) {
                throw new RuntimeBackendException("clientTools are not supported by the "
                        + "runtime backend yet — register tools on the agent definition");
            }
            ManagedSession session = ownedSession(request.sessionId()).orElseThrow(() ->
                    new RuntimeBackendException("Unknown session " + request.sessionId()));

            String responseId = "resp_" + UUID.randomUUID();
            ResponseAssembler assembler = new ResponseAssembler(responseId,
                    conversationOf(session), request.sessionId(), agentNameOf(session));
            // Settled: the response has ended, or the turn stopped to ask.
            CompletableFuture<Void> settled = new CompletableFuture<>();

            // Followed before the turn goes on: that it asks is told by its session, and only once.
            ai.mindconnect.agent.runtime.api.port.in.Subscription asking =
                    runtime.sessions().subscribe(
                            ai.mindconnect.agent.runtime.api.port.in.SessionEventFilter.session(session.id()).withChildSessions(), event -> {
                        if (event instanceof SessionEvent.TurnInterrupted interrupted && !settled.isDone()) {
                            assembler.waitingForApproval(interrupted.interruptions().stream()
                                    .filter(open -> Interruption.TOOL_APPROVAL.equals(open.kind())
                                            && open.call() != null)
                                    .map(AgentRuntimeBackend::toApprovalRequest).toList());
                            settled.complete(null);
                        }
                    });
            settled.whenComplete((done, failure) -> asking.close());

            TurnId turnId;
            try {
                turnId = request.input().size() == 1
                        && request.input().get(0) instanceof ConversationItem.ApprovalResponse answer
                        ? answered(session, answer)
                        : submitted(session, prepareInput(request));
            } catch (RuntimeException e) {
                settled.complete(null);
                throw e;
            }
            owners.put(responseId, caller.get());
            assemblers.put(responseId, assembler);
            turnOf.put(responseId, turnId);
            assembler.addMetadata("mc.turnId", turnId.value());

            ai.mindconnect.agent.runtime.api.port.in.Subscription following =
                    runtime.turns().subscribe(turnId, new TurnListener() {
                        @Override
                        public void onEvent(TurnEvent event) {
                            if (!settled.isDone()) {
                                heard(assembler, turnId, event);
                            }
                        }

                        @Override
                        public void onFinished(Turn turn) {
                            finished(assembler, turn);
                            settled.complete(null);
                        }
                    });
            settled.whenComplete((done, failure) -> following.close());
            // A turn that was over before anyone listened is told from how it stands.
            runtime.turns().find(turnId).filter(turn -> turn.status().isFinal()).ifPresent(turn -> {
                finished(assembler, turn);
                settled.complete(null);
            });

            if (!request.background()) {
                settled.join();   // the outcome is in the assembler once this settled
            }
            return assembler.snapshot();
        }

        /** A new turn of the session, on its agent as it stands now. */
        private TurnId submitted(ManagedSession session, List<ai.mindconnect.agent.loop.domain.ContentPart> input) {
            // A stored agent the session refers to holds as edited since.
            AgentDefinition agent = sessionManagement().agentOf(session.id()).orElseThrow(() ->
                    new RuntimeBackendException("Session " + session.id().value() + " has no agent"));
            runtime.sessions().changeAgent(session.id(), agent);
            return runtime.turns().submit(new TurnRequest(session.id(), input, Map.of()));
        }

        /** The turn that waits for this answer, going on with it. */
        private TurnId answered(ManagedSession session, ConversationItem.ApprovalResponse answer) {
            Turn waiting = runtime.turns().runningTurn(session.id()).orElse(null);
            Optional<String> asked = waiting == null ? Optional.empty() : waiting.interruptions().stream()
                    .filter(open -> open.call() != null && open.call().callId().equals(answer.requestId()))
                    .map(Interruption::id).findFirst();
            String interruption = asked.orElseThrow(() -> new RuntimeBackendException("No open approval request "
                    + answer.requestId() + " in session " + session.id().value()));
            runtime.turns().resume(waiting.id(), List.of(answer.approved()
                    ? InterruptionAnswer.approve(interruption) : InterruptionAnswer.reject(interruption, "")));
            return waiting.id();
        }

        /** What the turn, or a turn of an agent it called, reports. */
        private void heard(ResponseAssembler assembler, TurnId turnId, TurnEvent event) {
            if (turnId.value().equals(event.requestId())) {
                assembler.accept(event);
                return;
            }
            // Of a called agent's turn only that it runs and how it ended: the rest is its own business.
            Optional<Turn> below = runtime.turns().find(TurnId.of(event.requestId()));
            assembler.subAgentStarted(event.requestId(),
                    below.map(turn -> turn.agent().name()).orElse("agent"),
                    below.map(turn -> turn.sessionId().value()).orElse(null));
            if (event instanceof TurnEvent.TurnEnded ended
                    && ended.outcome().status() != TurnOutcome.Status.WAITING_FOR_TOOLS) {
                assembler.subAgentEnded(event.requestId(), ended.outcome().text());
            }
        }

        /** The turn is over: whatever its own events did not already say, its status does. */
        private void finished(ResponseAssembler assembler, Turn turn) {
            if (turn.status() == TurnStatus.CANCELLED) {
                assembler.cancelled();
            } else if (turn.outcome() != null) {
                assembler.accept(new TurnEvent.TurnEnded(turn.id().value(), turn.outcome()));
            } else {
                assembler.fail(turn.error() != null ? turn.error() : "The turn failed");
            }
        }

        @Override
        public Optional<Response> get(String responseId) {
            return ownedResponse(responseId).map(ResponseAssembler::snapshot);
        }

        @Override
        public boolean cancel(String responseId) {
            if (ownedResponse(responseId).isEmpty()) {
                return false;
            }
            TurnId turnId = turnOf.get(responseId);
            Optional<Turn> turn = turnId == null ? Optional.empty() : runtime.turns().find(turnId);
            if (turn.isEmpty() || turn.get().status().isFinal()) {
                return false;
            }
            runtime.turns().cancel(turnId);
            return true;
        }

        @Override
        public Subscription subscribe(SubscribeRequest request, Consumer<ResponseEvent> consumer) {
            ResponseAssembler assembler = ownedResponse(request.responseId()).orElseThrow(() ->
                    new RuntimeBackendException("Unknown response " + request.responseId()));
            // includeChildren: a called agent's turn is folded into the parent's
            // items by the assembler — nothing separate to merge yet.
            return assembler.subscribe(request.afterSeq(), consumer);
        }

        /**
         * The user's message as a turn's input: its text, and each image as a
         * part of its own. A document does not travel with the message — it is
         * attached to the session, where the agent finds it.
         */
        private List<ai.mindconnect.agent.loop.domain.ContentPart> prepareInput(ResponseRequest request) {
            if (request.input().size() != 1
                    || !(request.input().get(0) instanceof ConversationItem.Message message)) {
                throw new RuntimeBackendException("The runtime backend currently accepts exactly "
                        + "one user message or one approval response as input");
            }
            SessionId sessionId = SessionId.of(request.sessionId());
            StringBuilder text = new StringBuilder();
            List<ai.mindconnect.agent.loop.domain.ContentPart> media = new ArrayList<>();
            for (ContentPart part : message.content()) {
                switch (part) {
                    case ContentPart.Text t -> {
                        if (!text.isEmpty()) text.append("\n");
                        text.append(t.text());
                    }
                    case ContentPart.Document d -> attachDocument(sessionId, d);
                    case ContentPart.Image i -> media.add(ProtocolParts.image(attachImage(sessionId, i)));
                    default -> throw new RuntimeBackendException("Content part not supported by "
                            + "the runtime backend yet: " + part.getClass().getSimpleName());
                }
            }
            if (text.isEmpty() && media.isEmpty()) {
                throw new RuntimeBackendException(
                        "The runtime backend needs a text or image part in the user message");
            }
            List<ai.mindconnect.agent.loop.domain.ContentPart> parts = new ArrayList<>();
            parts.add(new ai.mindconnect.agent.loop.domain.ContentPart.Text(text.toString()));
            parts.addAll(media);
            return List.copyOf(parts);
        }

        private void attachDocument(SessionId sessionId, ContentPart.Document doc) {
            fileAttacher.attach(sessionId, resolve(doc.source(), doc.name()));
        }

        private ai.mindconnect.filestore.StoredFile attachImage(SessionId sessionId, ContentPart.Image image) {
            var stored = resolve(image.source(), inlineImageName(image.source()));
            fileAttacher.attach(sessionId, stored);
            return stored;
        }

        /**
         * A name for an inline image — the session records attachments by
         * name, so two pictures in one conversation must not share one.
         */
        private static String inlineImageName(ContentPart.MediaSource source) {
            String extension = source instanceof ContentPart.MediaSource.Inline in
                    && in.mediaType() != null && in.mediaType().startsWith("image/")
                    ? "." + in.mediaType().substring("image/".length()).replace("jpeg", "jpg")
                    : "";
            return "image-" + UUID.randomUUID().toString().substring(0, 8) + extension;
        }

        /**
         * The stored file behind a media source: looked up (FileId) or stored
         * now (Inline). A file the caller may not read is unknown — attaching
         * it would put its content in front of the caller's model.
         */
        private ai.mindconnect.filestore.StoredFile resolve(ContentPart.MediaSource source, String name) {
            requireFiles();
            UserId user = caller.get();
            return switch (source) {
                case ContentPart.MediaSource.FileId f -> fileStore.find(ai.mindconnect.filestore.FileId.of(f.fileId()))
                        .filter(stored -> stored.readableBy(user))
                        .orElseThrow(() -> new RuntimeBackendException(
                                "Unknown file id " + f.fileId() + " — upload via files() first"));
                case ContentPart.MediaSource.Inline in -> storeInline(name, in, user);
                case ContentPart.MediaSource.Url u -> throw new RuntimeBackendException(
                        "Url media sources are not supported by the runtime backend yet");
            };
        }

        private ai.mindconnect.filestore.StoredFile storeInline(String name,
                                                                ContentPart.MediaSource.Inline in,
                                                                UserId user) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(in.base64Data());
                return fileStore.save(name, in.mediaType(), new ByteArrayInputStream(bytes), user);
            } catch (Exception e) {
                throw new RuntimeBackendException("Failed to store inline document: " + e.getMessage(), e);
            }
        }
    };

    /** The question a tool call waits on, as the item a client answers. */
    static ConversationItem.ApprovalRequest toApprovalRequest(Interruption open) {
        return new ConversationItem.ApprovalRequest(open.call().callId(),
                ConversationItem.ApprovalRequest.TOOL_APPROVAL,
                Map.of("callId", open.call().callId(), "name", open.call().toolName(),
                        "arguments", open.call().arguments()),
                null);
    }

    private final Files filesApi = new Files() {

        @Override
        public StoredFile upload(String filename, String mediaType, byte[] content) {
            requireFiles();
            UserId user = caller.get();
            try {
                var stored = fileStore.save(filename, mediaType, new ByteArrayInputStream(content), user);
                return toProtocolFile(stored);
            } catch (Exception e) {
                throw new RuntimeBackendException("Upload failed: " + e.getMessage(), e);
            }
        }

        /** The caller's file — or one stored before creators were recorded, which anyone may read by id. */
        @Override
        public Optional<StoredFile> get(String fileId) {
            requireFiles();
            UserId user = caller.get();
            return fileStore.find(ai.mindconnect.filestore.FileId.of(fileId))
                    .filter(stored -> stored.readableBy(user))
                    .map(AgentRuntimeBackend::toProtocolFile);
        }
    };

    private static StoredFile toProtocolFile(ai.mindconnect.filestore.StoredFile stored) {
        return new StoredFile(stored.id().value(), stored.name(), stored.contentType(), stored.size());
    }

    private void requireFiles() {
        if (fileStore == null || fileAttacher == null) {
            throw new RuntimeBackendException("File support is not wired — call "
                    + "withFiles(fileStore, attacher)");
        }
    }

    private final Conversations conversationsApi = new Conversations() {

        @Override
        public Conversation create() {
            throw new RuntimeBackendException("Standalone conversations are not supported yet — "
                    + "the runtime creates the conversation when a session opens");
        }

        /** A conversation is the caller's session of that id: nobody reads another one's. */
        @Override
        public Optional<Conversation> get(String conversationId) {
            return ownedSession(conversationId)
                    .map(session -> new Conversation(conversationOf(session), session.createdAt()));
        }

        @Override
        public ConversationItemRecord append(String conversationId, ConversationItem item) {
            throw new RuntimeBackendException("Direct append is not supported yet — items are "
                    + "appended by runtime turns (native item storage is concept 9)");
        }

        @Override
        public List<ConversationItemRecord> items(String conversationId, long afterSeq, int limit) {
            return ownedSession(conversationId).map(session -> runtime.service(MessageRepository.class)
                            .findByConversation(ConversationId.of(conversationOf(session)), EVERYTHING).stream()
                            .filter(m -> m.sequenceNum() > afterSeq)
                            .limit(limit)
                            .map(this::toItem)
                            .toList())
                    .orElse(List.of());
        }

        /** Lossy mapping from the stored messages — precise item storage is concept 9's native store. */
        private ConversationItemRecord toItem(Message m) {
            String content = m.compressed() && m.compressedContent() != null
                    ? m.compressedContent() : m.content();
            ConversationItem item = switch (m.type()) {
                case CHAT -> ProtocolParts.message(m.senderType() == ParticipantType.USER
                        ? ai.mindconnect.agent.protocol.item.Role.USER
                        : ai.mindconnect.agent.protocol.item.Role.ASSISTANT, m);
                case TOOL_CALL -> new ConversationItem.FunctionCall(
                        m.id().value(), "tool_calls", Map.of("_raw", content));
                case TOOL_RESULT -> new ConversationItem.FunctionCallOutput(m.id().value(), content, false);
                default -> ConversationItem.Message.assistant(content);
            };
            return new ConversationItemRecord(m.id().value(), m.sequenceNum(), item);
        }
    };
}
