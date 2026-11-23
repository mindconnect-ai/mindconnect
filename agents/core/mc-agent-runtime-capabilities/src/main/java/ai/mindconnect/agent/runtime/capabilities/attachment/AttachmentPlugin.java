package ai.mindconnect.agent.runtime.capabilities.attachment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.hook.ModelCall;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.hook.TurnInput;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.plugin.BeforeOutcome;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Files attached to a session: the model is told what was attached and what
 * was removed, in the user's turn, where the question is.
 *
 * <p>The files stand in the session's metadata. The first user message after
 * a change records it in its own metadata — {@link #ANNOUNCED} for files
 * attached since, {@link #DETACHED} for files removed since — and carries
 * the images and PDFs among the new ones as parts. That record is never
 * rewritten; read in order it says what the model has been told, and the
 * next turn announces only the difference. The wording is made when the
 * model is asked: the stored text stays what the user typed, and a notice
 * names only what still holds.
 */
public final class AttachmentPlugin implements Plugin {

    /**
     * The key in a session's metadata that holds its attached files: a list
     * of entries with {@code id}, {@code name}, {@code mediaType},
     * {@code sizeBytes} and {@code path}, see {@link Attachment}.
     */
    public static final String ATTACHMENTS = "attachedFiles";

    /** The key in a user message's metadata: the names of the files it announced as attached. */
    public static final String ANNOUNCED = "attachments";

    /** The key in a user message's metadata: the names of the files it announced as removed. */
    public static final String DETACHED = "detached";

    @Override
    public String name() {
        return "attachments";
    }

    @Override
    public void install(PluginRegistry registry) {
        Supplier<SessionStore> sessions = registry.supplier(SessionStore.class);
        registry.contribute(ToolResolver.class, ToolResolver.of(List.of(new ViewAttachmentTool())));
        registry.before(RuntimeHook.TURN_INPUT, input -> BeforeOutcome.proceed(new TurnInput(input.session(),
                announcing(input, sessions.get().messages(input.session().id())))));
        registry.after(RuntimeHook.INSTRUCTIONS, (context, instructions) ->
                instructions + listing(Attachment.of(context.session())));
        // Offered without the agent naming it: whoever is shown a file once must be able to ask for it again.
        registry.after(RuntimeHook.TOOL_OFFER, (context, tools) -> {
            if (Attachment.of(context.session()).stream().noneMatch(Attachment::sendableAsPart)
                    || tools.stream().anyMatch(tool -> ViewAttachmentTool.NAME.equals(tool.name()))) {
                return tools;
            }
            List<ToolDefinition> offered = new ArrayList<>(tools);
            offered.add(ViewAttachmentTool.DEFINITION);
            return offered;
        });
        registry.before(RuntimeHook.MODEL_CALL, call -> BeforeOutcome.proceed(call.with(forModel(call))));
    }

    /** The request with what changed among the attachments since the model was last told. */
    private static TurnRequest announcing(TurnInput input, List<TurnMessage> history) {
        TurnRequest request = input.request();
        TurnMessage message = userMessage(Attachment.of(input.session()), history, request.input(),
                request.metadata());
        return new TurnRequest(request.sessionId(), message.content(), request.agent(), message.metadata());
    }

    /**
     * The user's message as the next turn stores it: the input, the images
     * and PDFs attached since the model was last told, and the record of
     * what the message announces. For whoever shows the message before the
     * turn has written it.
     *
     * @param history what was said in the session so far
     */
    public static TurnMessage userMessage(List<Attachment> attached, List<TurnMessage> history,
                                          List<ContentPart> input, Map<String, Object> inputMetadata) {
        Set<String> told = told(history);
        List<Attachment> fresh = attached.stream().filter(file -> !told.contains(file.name())).toList();
        List<String> gone = told.stream()
                .filter(name -> attached.stream().noneMatch(file -> file.name().equals(name)))
                .toList();
        // A file the caller already sends as a part of its own is not added a second time.
        Set<String> sent = new LinkedHashSet<>();
        input.forEach(part -> fileOf(part).ifPresent(sent::add));
        List<ContentPart> parts = new ArrayList<>(input);
        fresh.stream().filter(Attachment::sendableAsPart).filter(file -> !sent.contains(file.id()))
                .forEach(file -> parts.add(file.part()));
        Map<String, Object> metadata = new LinkedHashMap<>(inputMetadata);
        if (!fresh.isEmpty()) {
            metadata.put(ANNOUNCED, fresh.stream().map(Attachment::name).toList());
        }
        if (!gone.isEmpty()) {
            metadata.put(DETACHED, gone);
        }
        return new TurnMessage(null, null, TurnMessage.Type.USER, parts, List.of(), metadata);
    }

    /** What the model has been told is attached, after reading the history in order. */
    private static Set<String> told(List<TurnMessage> history) {
        Set<String> told = new LinkedHashSet<>();
        for (TurnMessage message : history) {
            if (message.type() == TurnMessage.Type.USER) {
                told.addAll(names(message, ANNOUNCED));
                names(message, DETACHED).forEach(told::remove);
            }
        }
        return told;
    }

    private static List<String> names(TurnMessage message, String key) {
        return message.metadata().get(key) instanceof Collection<?> names
                ? names.stream().map(String::valueOf).toList() : List.of();
    }

    private static java.util.Optional<String> fileOf(ContentPart part) {
        MediaSource source = part instanceof ContentPart.Image image ? image.source()
                : part instanceof ContentPart.Document document ? document.source() : null;
        return source instanceof MediaSource.FileId file ? java.util.Optional.of(file.fileId())
                : java.util.Optional.empty();
    }

    /** The list in the instructions, made anew for every call, so a removed file leaves it at once. */
    private static String listing(List<Attachment> attached) {
        // An image is not searched: it goes with its message, so it is listed only where it lies on disk.
        List<Attachment> listed = attached.stream().filter(file -> !file.isImage() || file.path() != null).toList();
        if (listed.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\n## Attached files\n\n"
                + "The user attached these files to this conversation:\n");
        listed.forEach(file -> text.append("- ").append(file.listing()).append('\n'));
        return text.append(howToRead(listed)).append('\n').toString();
    }

    private static String howToRead(List<Attachment> files) {
        boolean onDisk = files.stream().anyMatch(file -> file.path() != null);
        boolean searchedOnly = files.stream().anyMatch(file -> file.path() == null);
        if (!onDisk) {
            return "They are indexed for search. Read their content with vector_search; they are not on the "
                    + "filesystem, so file and document tools cannot open them.";
        }
        return searchedOnly
                ? "Open a file on disk by its path with the file and document tools; a file without a path is "
                        + "not on the filesystem — read its content with vector_search."
                : "Open a file on disk by its path with the file and document tools.";
    }

    /**
     * The history as the model reads it in this call: a notice ahead of the
     * user's text for what a message announced and what still holds, and
     * after the results of this turn's {@code view_attachment} calls the
     * file each one asked for. Only the request changes; the session keeps
     * what was said.
     */
    private static ModelRequest forModel(ModelCall call) {
        Session session = call.context().session();
        List<Attachment> attached = Attachment.of(session);
        String turnId = call.context().turnId().value();
        List<TurnMessage> messages = new ArrayList<>();
        List<TurnMessage> shownAgain = new ArrayList<>();
        Map<String, String> askedFor = new LinkedHashMap<>();
        for (TurnMessage message : call.request().messages()) {
            boolean ofATool = message.type() == TurnMessage.Type.TOOL_RESULT
                    || message.type() == TurnMessage.Type.TOOL_DISPATCHED;
            if (!ofATool) {
                // After the results of a round, never between them: a provider wants those together.
                messages.addAll(shownAgain);
                shownAgain.clear();
            }
            if (message.type() == TurnMessage.Type.TOOL_CALL) {
                message.toolCalls().stream().filter(asked -> ViewAttachmentTool.NAME.equals(asked.name()))
                        .forEach(asked -> askedFor.put(asked.callId(), String.valueOf(asked.arguments().get("name"))));
            }
            if (message.type() == TurnMessage.Type.TOOL_RESULT && turnId.equals(message.turnId())
                    && !Boolean.TRUE.equals(message.metadata().get("failed"))) {
                String name = askedFor.get(String.valueOf(message.metadata().get("callId")));
                attached.stream().filter(file -> file.name().equals(name)).filter(Attachment::sendableAsPart)
                        .findFirst().ifPresent(file -> shownAgain.add(new TurnMessage(null, turnId,
                                TurnMessage.Type.USER, List.of(new ContentPart.Text("[" + file.name() + " — "
                                        + file.kind() + " shown again at the assistant's request]"), file.part()),
                                List.of(), Map.of())));
            }
            messages.add(message.type() == TurnMessage.Type.USER ? withNotice(message, attached) : message);
        }
        messages.addAll(shownAgain);
        ModelRequest request = call.request();
        return new ModelRequest(request.turnId(), request.settings(), request.instructions(), messages,
                request.tools());
    }

    private static TurnMessage withNotice(TurnMessage message, List<Attachment> attached) {
        List<String> removed = names(message, DETACHED).stream()
                .filter(name -> attached.stream().noneMatch(file -> file.name().equals(name)))
                .toList();
        // An image is announced on the message but not in words: its part speaks for it.
        List<Attachment> added = attached.stream()
                .filter(file -> names(message, ANNOUNCED).contains(file.name()))
                .filter(file -> !file.isImage())
                .toList();
        StringBuilder notice = new StringBuilder();
        if (!removed.isEmpty()) {
            notice.append("[System note — removed from this chat: ").append(String.join(", ", removed))
                    .append(" — the content is no longer available. If asked about it, say that the file was "
                            + "removed; do not search for it and do not look it up elsewhere.]\n\n");
        }
        if (!added.isEmpty()) {
            notice.append("[System note — attached to this chat: ")
                    .append(String.join(", ", added.stream().map(Attachment::listing).toList()))
                    .append(". ").append(howToRead(added)).append("]\n\n");
        }
        if (notice.isEmpty()) {
            return message;
        }
        List<ContentPart> content = new ArrayList<>();
        content.add(new ContentPart.Text(notice.toString()));
        content.addAll(message.content());
        return new TurnMessage(message.id(), message.turnId(), message.type(), content, message.toolCalls(),
                message.metadata());
    }
}
