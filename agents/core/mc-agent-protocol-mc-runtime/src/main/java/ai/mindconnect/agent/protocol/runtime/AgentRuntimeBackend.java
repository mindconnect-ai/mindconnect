package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
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
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
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

/**
 * Backend adapter: the protocol surface implemented against the Mindconnect
 * agent runtime. The runtime is the server side OpenAI cannot be: registered
 * tools execute inside the turn (the inner loop), so {@code clientTools}
 * are rejected here until the runtime supports per-request tools.
 *
 * <pre>
 * var backend = new AgentRuntimeBackend(chatService, sessionService,
 *         definitionRepository, conversationManager, "user-1");
 * Session s = backend.open("default", "travel-assistant");
 * Response r = backend.create(ResponseRequest.text(s.id(), "Hi!"));
 * </pre>
 *
 * <p>Like the OpenAI backend, the three surfaces are composed
 * ({@link #sessions()}, {@link #responses()}, {@link #conversations()})
 * because the interfaces' {@code get} methods collide on one class.
 *
 * <p>v1 mapping notes: ids are the runtime's UUIDs as strings; responses are
 * tracked in-memory (a restart forgets them — the conversation keeps the
 * durable truth); {@code Conversations.items} maps the legacy Message format
 * lossily until items are stored natively (concept 9).
 */
public final class AgentRuntimeBackend {

    private final AgentChatService chat;
    private final AgentSessionService sessionService;
    private final AgentDefinitionRepository definitions;
    private final ConversationManager conversationManager;
    private final String userId;

    private final Map<String, ResponseAssembler> assemblers = new ConcurrentHashMap<>();
    private final Map<String, ChatTurnHandle> handles = new ConcurrentHashMap<>();

    private ai.mindconnect.filestore.FileStore fileStore;
    private FileAttacher fileAttacher;

    public AgentRuntimeBackend(AgentChatService chat, AgentSessionService sessionService,
                               AgentDefinitionRepository definitions,
                               ConversationManager conversationManager, String userId) {
        this.chat = chat;
        this.sessionService = sessionService;
        this.definitions = definitions;
        this.conversationManager = conversationManager;
        this.userId = userId;
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

    // ── The three protocol surfaces, by composition ─────────────────────────

    public Sessions sessions() { return sessionsApi; }

    public AgentResponses responses() { return responsesApi; }

    public Conversations conversations() { return conversationsApi; }

    public Files files() { return filesApi; }

    public Session open(String namespace, String agentName) { return sessionsApi.open(namespace, agentName); }

    public Response create(ResponseRequest request) { return responsesApi.create(request); }

    private final Sessions sessionsApi = new Sessions() {

        @Override
        public Session open(String namespace, String agentName) {
            Namespace ns = new Namespace(namespace);
            AgentDefinition def = definitions.findByName(ns, agentName)
                    .orElseThrow(() -> new RuntimeBackendException(
                            "No agent named '" + agentName + "' in namespace '" + namespace + "'"));
            AgentSession session = sessionService.openChat(def.id(), ns, userId);
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
            try {
                AgentSession session = sessionService.findSession(UUID.fromString(sessionId));
                String agentName = definitions.findById(session.agentDefinitionId())
                        .map(AgentDefinition::name).orElse("unknown");
                return Optional.of(toProtocol(session, agentName));
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        private Session toProtocol(AgentSession session, String agentName) {
            return new Session(session.id().toString(), session.namespace().value(),
                    session.conversationId().toString(), agentName, session.startedAt());
        }
    };

    private final AgentResponses responsesApi = new AgentResponses() {

        @Override
        public Response create(ResponseRequest request) {
            if (!request.clientTools().isEmpty()) {
                throw new RuntimeBackendException("clientTools are not supported by the "
                        + "runtime backend yet — register tools on the agent definition");
            }
            AgentSession session = sessionService.findSession(UUID.fromString(request.sessionId()));
            String agentName = definitions.findById(session.agentDefinitionId())
                    .map(AgentDefinition::name).orElse("unknown");

            String responseId = "resp_" + UUID.randomUUID();
            ResponseAssembler assembler = new ResponseAssembler(responseId,
                    session.conversationId().toString(), request.sessionId(), agentName);
            assemblers.put(responseId, assembler);

            ChatTurnHandle handle = chat.submitChat(
                    UUID.fromString(request.sessionId()), prepareInput(request), assembler::accept);
            handles.put(responseId, handle);
            assembler.addMetadata("mc.turnId", handle.id().toString());
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
            return Optional.ofNullable(assemblers.get(responseId)).map(ResponseAssembler::snapshot);
        }

        @Override
        public boolean cancel(String responseId) {
            ChatTurnHandle handle = handles.get(responseId);
            return handle != null && handle.cancel();
        }

        @Override
        public Subscription subscribe(SubscribeRequest request, Consumer<ResponseEvent> consumer) {
            ResponseAssembler assembler = assemblers.get(request.responseId());
            if (assembler == null) {
                throw new RuntimeBackendException("Unknown response " + request.responseId());
            }
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
            UUID sessionId = UUID.fromString(request.sessionId());
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

        private void attachDocument(UUID sessionId, ContentPart.Document doc) {
            fileAttacher.attach(sessionId, resolve(doc.source(), doc.name()));
        }

        /** The stored image, attached to the session (recorded, viewer activated — never ingested). */
        private ai.mindconnect.filestore.StoredFile attachImage(UUID sessionId, ContentPart.Image image) {
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

        /** The stored file behind a media source: looked up (FileId) or stored now (Inline). */
        private ai.mindconnect.filestore.StoredFile resolve(ContentPart.MediaSource source, String name) {
            requireFiles();
            return switch (source) {
                case ContentPart.MediaSource.FileId f -> fileStore.find(f.fileId())
                        .orElseThrow(() -> new RuntimeBackendException(
                                "Unknown file id " + f.fileId() + " — upload via files() first"));
                case ContentPart.MediaSource.Inline in -> storeInline(name, in);
                case ContentPart.MediaSource.Url u -> throw new RuntimeBackendException(
                        "Url media sources are not supported by the runtime backend yet");
            };
        }

        private ai.mindconnect.filestore.StoredFile storeInline(String name,
                                                                ContentPart.MediaSource.Inline in) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(in.base64Data());
                return fileStore.save(name, in.mediaType(), new ByteArrayInputStream(bytes));
            } catch (Exception e) {
                throw new RuntimeBackendException("Failed to store inline document: " + e.getMessage(), e);
            }
        }
    };

    private final Files filesApi = new Files() {

        @Override
        public StoredFile upload(String filename, String mediaType, byte[] content) {
            requireFiles();
            try {
                var stored = fileStore.save(filename, mediaType, new ByteArrayInputStream(content));
                return toProtocolFile(stored);
            } catch (Exception e) {
                throw new RuntimeBackendException("Upload failed: " + e.getMessage(), e);
            }
        }

        @Override
        public Optional<StoredFile> get(String fileId) {
            requireFiles();
            return fileStore.find(fileId).map(AgentRuntimeBackend::toProtocolFile);
        }
    };

    private static StoredFile toProtocolFile(ai.mindconnect.filestore.StoredFile stored) {
        return new StoredFile(stored.id(), stored.name(), stored.contentType(), stored.size());
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
        public Conversation create(String namespace) {
            throw new RuntimeBackendException("Standalone conversations are not supported yet — "
                    + "the runtime creates the conversation when a session opens");
        }

        @Override
        public Optional<Conversation> get(String conversationId) {
            return conversationManager.findById(UUID.fromString(conversationId))
                    .map(c -> new Conversation(c.id().toString(), c.namespace().value(), c.createdAt()));
        }

        @Override
        public ConversationItemRecord append(String conversationId, ConversationItem item) {
            throw new RuntimeBackendException("Direct append is not supported yet — items are "
                    + "appended by runtime turns (native item storage is concept 9)");
        }

        @Override
        public List<ConversationItemRecord> items(String conversationId, long afterSeq, int limit) {
            List<Message> history = conversationManager.loadHistory(
                    UUID.fromString(conversationId), new PageRequest(0, 1000));
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
                        m.id().toString(), "tool_calls", Map.of("_raw", content));
                case TOOL_RESULT -> new ConversationItem.FunctionCallOutput(m.id().toString(), content, false);
                default -> ConversationItem.Message.assistant(content);
            };
            return new ConversationItemRecord(m.id().toString(), m.sequenceNum(), item);
        }
    };
}
