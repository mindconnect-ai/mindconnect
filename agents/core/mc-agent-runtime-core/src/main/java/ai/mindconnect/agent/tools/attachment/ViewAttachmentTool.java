package ai.mindconnect.agent.tools.attachment;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.AttachedFile;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.service.prompt.AttachmentParts;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shows the model an attached image or PDF again. A file the model reads
 * inline goes with the message it was attached to, once — a request repeats
 * the whole history, and media repeated in every request costs its tokens
 * every time. From the next turn on a placeholder names it, and this tool
 * brings it back: not as the tool's result (a tool result carries text on
 * every provider) but as a message of its own in the conversation, on the
 * user's side and in the current turn, carrying the image or document part.
 * The mapper renders that message like any other user message with media,
 * so the model sees the picture in the very next request.
 */
public final class ViewAttachmentTool implements Tool {

    public static final String NAME = "view_attachment";

    /** Metadata key on the inserted message: which tool put it there. */
    public static final String INSERTED_BY = "insertedBy";

    /** Metadata key on the inserted message: the attachment's name. */
    public static final String ATTACHMENT = "attachment";

    private final AgentSessionRepository sessions;
    private final ConversationManager conversations;
    private final UUID sessionId;

    public ViewAttachmentTool(AgentSessionRepository sessions, ConversationManager conversations,
                              UUID sessionId) {
        this.sessions = sessions;
        this.conversations = conversations;
        this.sessionId = sessionId;
    }

    /** Was this message inserted by this tool — an attachment shown again, not something the user typed? */
    public static boolean insertedBy(Message message) {
        return message.metadata() != null && NAME.equals(message.metadata().get(INSERTED_BY));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Show an image or PDF attached to this chat again.

                An attached image or PDF is shown to you with the message it came with, once.
                In later turns a note names it instead. Call this tool with the file's name
                when you need to look at it again — it is shown to you right after this
                tool's result, as a message of its own. Files that are not images or PDFs
                are read with vector_search, not with this tool.
                """;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "The attached file's name, as shown in the chat's attachments")),
                "required", List.of("name"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object raw = arguments.get("name");
        String name = raw == null ? "" : raw.toString().trim();
        if (name.isEmpty()) return "Error: 'name' is required — the attached file's name.";

        AgentSession session = sessions.findById(sessionId).orElse(null);
        if (session == null) return "Error: session " + sessionId + " not found.";

        AttachedFile file = session.attachedFile(name).orElse(null);
        if (file == null) {
            List<String> names = session.attachedFileNames();
            return names.isEmpty()
                    ? "No file named '" + name + "' — nothing is attached to this chat."
                    : "No file named '" + name + "'. Attached to this chat: " + String.join(", ", names) + ".";
        }
        if (!file.sendableAsPart()) {
            return "'" + name + "' is not an image or PDF" + (file.id() == null ? " that can be shown again" : "")
                    + " — read its content with vector_search.";
        }

        // The message goes into the turn that is running — same turn id, same
        // run — so the turn's bookkeeping keeps it and the mapper sends the
        // media inline. Recorded before this result is, which the sanitizer
        // sorts out: results follow their calls, the message follows both.
        ConversationHistory history = conversations.loadCompleteHistory(session.conversationId());
        UUID turnId = history.currentTurnId().orElse(null);
        String kind = file.isImage() ? "image" : "document";
        List<ContentPart> parts = List.of(
                new ContentPart.Text("[" + name + " — " + kind + " shown again at the assistant's request]"),
                AttachmentParts.part(file));
        conversations.addMessageToConversation(session.conversationId(), UUID.randomUUID(),
                ParticipantType.USER, MessageType.CHAT, parts, turnId, history.currentRun(),
                Map.of(INSERTED_BY, NAME, ATTACHMENT, name));
        return "Showing '" + name + "' — the " + kind + " follows this result as a message of its own.";
    }
}
