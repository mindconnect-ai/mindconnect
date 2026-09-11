package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
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
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Backend adapter: the protocol surface implemented against the Mindconnect
 * agent runtime. The runtime is the server side OpenAI cannot be: registered
 * tools execute inside the turn (the inner loop), so {@code clientTools}
 * are rejected here until the runtime supports per-request tools.
 *
 * <pre>
 * var backend = new AgentRuntimeBackend(chatService, sessionService,
 *         definitionRepository, conversationManager, "user-1");
 * Session s = backend.open("travel-assistant");
 * Response r = backend.create(ResponseRequest.text(s.id(), "Hi!"));
 * </pre>
 *
 * <p>Like the OpenAI backend, the three surfaces are composed
 * ({@link #sessions()}, {@link #responses()}, {@link #conversations()})
 * because the interfaces' {@code get} methods collide on one class.
 *
 * <p>Everything is the caller's own. Sessions open for the caller, files are
 * stored as the caller's, and a session, response or file of someone else's
 * is not found — the answer a missing one gets. The caller is either fixed
 * (the {@code String} constructor: an embedding, the CLI) or asked on every
 * operation (the {@code Supplier} one: a server, whose caller is the current
 * request's). {@link #conversations()} is not scoped: the runtime cannot map a
 * conversation back to its session yet, and no served endpoint exposes it.
 *
 * <p>v1 mapping notes: ids are the runtime's id values as strings; responses are
 * tracked in-memory (a restart forgets them — the conversation keeps the
 * durable truth); {@code Conversations.items} maps the legacy Message format
 * lossily until items are stored natively (concept 9).
 */
public final class AgentRuntimeBackend {

    private final AgentChatService chat;
    private final AgentSessionService sessionService;
    private final AgentDefinitionRepository definitions;
    private final ConversationManager conversationManager;
    private final Supplier<UserId> caller;

    private final Map<String, ResponseAssembler> assemblers;
    private final Map<String, ChatTurnHandle> handles;
    /**
     * Whose session each response ran in. A session's owner never changes, so
     * it is noted once at creation — a stream refreshes its response on every
     * event, and must not read the session each time to learn this.
     */
    private final Map<String, UserId> owners;

    private ai.mindconnect.filestore.FileStore fileStore;
    private FileAttacher fileAttacher;


    /** A backend that always acts for {@code userId}. */
    public AgentRuntimeBackend(AgentChatService chat, AgentSessionService sessionService,
                               AgentDefinitionRepository definitions,
                               ConversationManager conversationManager, String userId) {
        this(chat, sessionService, definitions, conversationManager, fixed(UserId.of(userId)));
    }

    /**
     * A backend that acts for whoever {@code caller} names, asked on every
     * operation — a server passes the authenticated user of the current
     * request. The supplier may throw to refuse a request without one.
     */
    public AgentRuntimeBackend(AgentChatService chat, AgentSessionService sessionService,
                               AgentDefinitionRepository definitions,
                               ConversationManager conversationManager, Supplier<UserId> caller) {
        this(chat, sessionService, definitions, conversationManager, caller,
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    private AgentRuntimeBackend(AgentChatService chat, AgentSessionService sessionService,
                                AgentDefinitionRepository definitions,
                                ConversationManager conversationManager, Supplier<UserId> caller,
                                Map<String, ResponseAssembler> assemblers,
                                Map<String, ChatTurnHandle> handles,
                                Map<String, UserId> owners) {
        this.chat = chat;
        this.sessionService = sessionService;
        this.definitions = definitions;
        this.conversationManager = conversationManager;
        this.caller = caller;
        this.assemblers = assemblers;
        this.handles = handles;
        this.owners = owners;
    }

    private static Supplier<UserId> fixed(UserId user) {
        return () -> user;
    }

    /**
     * Enables the {@code Files} surface and {@code Document(FileId)} content
     * parts. Wire from the builder:
     * {@code .withFiles(runtime.fileStore(), runtime::attachStored)}.
     */
    public AgentRuntimeBackend withFiles(ai.mindconnect.filestore.FileStore fileStore,
                                         FileAttacher fileAttacher) {
        this.fileStore = fileStore;
        this.fileAttacher = fileAttacher;
        return this;
    }

    /** Who is calling now, as the caller supplier answers. */
    public UserId currentUser() {
        return caller.get();
    }

    /**
     * This backend acting for {@code user}, whatever the caller supplier would
     * say — the same responses, the same files. For work that outlives the
     * request thread: a stream's callbacks run on the channel's thread, where a
     * supplier that reads the current request has nobody to answer with. Ask
     * {@link #currentUser()} on the request thread and give the callbacks this.
     */
    public AgentRuntimeBackend actingFor(UserId user) {
        var view = new AgentRuntimeBackend(chat, sessionService, definitions, conversationManager,
                fixed(user), assemblers, handles, owners);
        view.fileStore = fileStore;
        view.fileAttacher = fileAttacher;
        return view;
    }

    // ── The three protocol surfaces, by composition ─────────────────────────

    public Sessions sessions() { return sessionsApi; }

    public AgentResponses responses() { return responsesApi; }

    public Conversations conversations() { return conversationsApi; }

    public Files files() { return filesApi; }

    public Session open(String agentName) { return sessionsApi.open(agentName); }

    public Response create(ResponseRequest request) { return responsesApi.create(request); }

    /**
     * The session, if it exists and is the caller's. A session id travels in
     * URLs and client state, so it opens nothing on its own.
     */
    private Optional<AgentSession> ownedSession(String sessionId) {
        UserId user = caller.get();
        try {
            return Optional.of(sessionService.findSession(SessionId.of(sessionId)))
                    .filter(session -> user.equals(session.userId()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The response's assembler, if the response ran in one of the caller's sessions. */
    private Optional<ResponseAssembler> ownedResponse(String responseId) {
        UserId user = caller.get();
        if (responseId == null || !user.equals(owners.get(responseId))) {
            return Optional.empty();
        }
        return Optional.ofNullable(assemblers.get(responseId));
    }

    private final Sessions sessionsApi = new Sessions() {

        @Override
        public Session open(String agentName) {
            AgentDefinition def = definitions.findByName(agentName)
                    .orElseThrow(() -> new RuntimeBackendException(
                            "No agent named '" + agentName + "'"));
            AgentSession session = sessionService.openChat(def.id(), caller.get());
            return toProtocol(session, agentName);
        }

        @Override
        public Session openOn(String conversationId, String agentName) {
            throw new RuntimeBackendException(
                    "openOn is not supported yet — the runtime binds the conversation "
                            + "when the session is created");
        }

        @Override
        public Optional<Session> get(String sessionId) {
            return ownedSession(sessionId).map(session -> {
                String agentName = definitions.findById(session.agentDefinitionId())
                        .map(AgentDefinition::name).orElse("unknown");
                return toProtocol(session, agentName);
            });
        }

        private Session toProtocol(AgentSession session, String agentName) {
            return new Session(session.id().value(),
                    session.conversationId().value(), agentName, session.startedAt());
        }
    };

    private final AgentResponses responsesApi = new AgentResponses() {

        @Override
        public Response create(ResponseRequest request) {
            if (!request.clientTools().isEmpty()) {
                throw new RuntimeBackendException("clientTools are not supported by the "
                        + "runtime backend yet — register tools on the agent definition");
            }
            AgentSession session = ownedSession(request.sessionId()).orElseThrow(() ->
                    new RuntimeBackendException("Unknown session " + request.sessionId()));
            String agentName = definitions.findById(session.agentDefinitionId())
                    .map(AgentDefinition::name).orElse("unknown");

            String responseId = "resp_" + UUID.randomUUID();
            ResponseAssembler assembler = new ResponseAssembler(responseId,
                    session.conversationId().value(), request.sessionId(), agentName);
            owners.put(responseId, session.userId());
            assemblers.put(responseId, assembler);

            ChatTurnHandle handle = chat.submitChat(
                    session.id(), prepareInput(request), assembler::accept);
            handles.put(responseId, handle);
            assembler.addMetadata("mc.turnId", handle.id().value());
            handle.result().whenComplete((text, ex) -> {
                if (ex instanceof CancellationException
                        || ex != null && ex.getCause() instanceof CancellationException) {
                    assembler.cancelled();
                } else if (ex != null) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    assembler.fail(cause.getMessage());
                }
                // success: StreamEvent.Done already completed the assembler
            });

            if (request.background()) {
                return assembler.snapshot();
            }
            handle.result().exceptionally(ex -> null).join();   // outcome is in the assembler
            return assembler.snapshot();
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
            ChatTurnHandle handle = handles.get(responseId);
            return handle != null && handle.cancel();
        }

        @Override
        public Subscription subscribe(SubscribeRequest request, Consumer<ResponseEvent> consumer) {
            ResponseAssembler assembler = ownedResponse(request.responseId()).orElseThrow(() ->
                    new RuntimeBackendException("Unknown response " + request.responseId()));
            // includeChildren: sub-agent events are folded into the parent's
            // items by the assembler (v1) — nothing separate to merge yet.
            long afterSeq = request.afterSeq() == Long.MAX_VALUE ? Long.MAX_VALUE : request.afterSeq();
            return assembler.subscribe(afterSeq, consumer);
        }

        /**
         * One user message in, the conversation's content parts out — after
         * side effects. A {@code Document} part is resolved (FileId) or stored
         * (Inline) and attached to the session via the {@link FileAttacher}:
         * ingested for retrieval, and — a PDF — sent with the message as a
         * document part by the chat facade, which announces every fresh
         * attachment. An {@code Image} part is resolved or stored, attached
         * the same way — the session records it and {@code view_attachment}
         * is activated, so the model can ask for it again in a later turn —
         * and goes with the message directly, as the image part a vision
         * model reads; the facade does not add it a second time.
         */
        private List<ai.mindconnect.message.domain.ContentPart> prepareInput(ResponseRequest request) {
            if (request.input().size() != 1
                    || !(request.input().get(0) instanceof ConversationItem.Message message)) {
                throw new RuntimeBackendException("The runtime backend currently accepts exactly "
                        + "one user message as input (approvals come with the native item store)");
            }
            SessionId sessionId = SessionId.of(request.sessionId());
            StringBuilder text = new StringBuilder();
            List<ai.mindconnect.message.domain.ContentPart> media = new java.util.ArrayList<>();
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
            List<ai.mindconnect.message.domain.ContentPart> parts = new java.util.ArrayList<>();
            parts.add(new ai.mindconnect.message.domain.ContentPart.Text(text.toString()));
            parts.addAll(media);
            return List.copyOf(parts);
        }

        private void attachDocument(SessionId sessionId, ContentPart.Document doc) {
            fileAttacher.attach(sessionId, resolve(doc.source(), doc.name()));
        }

        /** The stored image, attached to the session (recorded, viewer activated — never ingested). */
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
                    + "withFiles(fileStore, attacher), e.g. "
                    + "withFiles(runtime.fileStore(), runtime::attachStored)");
        }
    }

    private final Conversations conversationsApi = new Conversations() {

        @Override
        public Conversation create() {
            throw new RuntimeBackendException("Standalone conversations are not supported yet — "
                    + "the runtime creates the conversation when a session opens");
        }

        @Override
        public Optional<Conversation> get(String conversationId) {
            return conversationManager.findById(ConversationId.of(conversationId))
                    .map(c -> new Conversation(c.id().value(), c.createdAt()));
        }

        @Override
        public ConversationItemRecord append(String conversationId, ConversationItem item) {
            throw new RuntimeBackendException("Direct append is not supported yet — items are "
                    + "appended by runtime turns (native item storage is concept 9)");
        }

        @Override
        public List<ConversationItemRecord> items(String conversationId, long afterSeq, int limit) {
            List<Message> history = conversationManager.loadHistory(
                    ConversationId.of(conversationId), new PageRequest(0, 1000));
            return history.stream()
                    .filter(m -> m.sequenceNum() > afterSeq)
                    .limit(limit)
                    .map(this::toItem)
                    .toList();
        }

        /** Lossy legacy mapping — precise item storage is concept 9's native store. */
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
