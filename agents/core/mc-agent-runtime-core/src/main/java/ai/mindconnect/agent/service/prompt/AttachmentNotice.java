package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.ParticipantType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tells the model about a file the user attached — in the user's turn, where
 * the question is, not only in the system prompt where it is a footnote.
 *
 * <p>Two halves, kept apart on purpose. The bookkeeping: the first user
 * message after an upload records the names it announces in its metadata
 * under {@link #ATTACHMENTS} — written by {@code AgentChatService} when the
 * message is persisted; later turns see the file in the system prompt's
 * standing list, so nothing is announced twice. The wording: {@link #notice}
 * renders that metadata into the line the model reads, when the request is
 * built ({@code MessageToLlmMessageMapper}) — the stored text stays what the
 * user typed, and the chat UI shows the same metadata as a chip. Origin in
 * the record, presentation at the consumer. A file removed from the chat
 * simply never gets announced.
 */
public final class AttachmentNotice {

    /** Message metadata key: the attachment names this user message announced. */
    public static final String ATTACHMENTS = "attachments";

    private AttachmentNotice() {
    }

    /** The session's attachments that no earlier user message has announced yet, in attach order. */
    public static List<String> unannounced(AgentSession session, List<Message> history) {
        if (session == null || session.attachedFiles().isEmpty()) {
            return List.of();
        }
        Set<String> announced = new LinkedHashSet<>();
        for (Message m : history) {
            if (m.senderType() != ParticipantType.USER || m.metadata() == null) continue;
            Object names = m.metadata().get(ATTACHMENTS);
            if (names instanceof Collection<?> c) {
                c.forEach(n -> announced.add(String.valueOf(n)));
            }
        }
        List<String> fresh = new ArrayList<>();
        for (String file : session.attachedFiles()) {
            if (!announced.contains(file)) fresh.add(file);
        }
        return fresh;
    }

    /** The attachment names a stored message announces, from its metadata; empty when none. */
    public static List<String> announcedBy(Message message) {
        if (message == null || message.metadata() == null) return List.of();
        Object names = message.metadata().get(ATTACHMENTS);
        if (!(names instanceof Collection<?> c) || c.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        c.forEach(n -> out.add(String.valueOf(n)));
        return out;
    }

    /**
     * The line that goes ahead of the user's text in the model's view — what
     * was attached, what kind of file it is, and the one instruction that
     * matters: the content is reached through {@code vector_search}, never
     * through a path. Marked as a system note so the model does not take it
     * for something the user said.
     */
    public static String notice(List<String> files) {
        if (files.isEmpty()) return "";
        StringBuilder out = new StringBuilder("[System note — attached to this chat: ");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(files.get(i)).append(" (").append(kind(files.get(i))).append(')');
        }
        return out.append(" — indexed for search. Read their content with vector_search; ")
                .append("they are not on the filesystem, so file and document tools cannot open them.]")
                .toString();
    }

    /** The user's text as the model reads it: the notice ahead of it when the message announces files, the text alone otherwise. */
    public static String forModel(Message message) {
        List<String> files = announcedBy(message);
        return files.isEmpty() ? message.content() : notice(files) + "\n\n" + message.content();
    }

    /** Metadata for the message that carries the notice. */
    public static Map<String, Object> metadata(List<String> files) {
        return files.isEmpty() ? Map.of() : Map.of(ATTACHMENTS, List.copyOf(files));
    }

    /** A human word for the file type, from the extension — enough for the model to stop treating "x.pdf" as a path. */
    static String kind(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "pdf" -> "PDF";
            case "doc", "docx" -> "Word document";
            case "xls", "xlsx" -> "Excel sheet";
            case "ppt", "pptx" -> "presentation";
            case "md", "markdown" -> "Markdown";
            case "txt" -> "text file";
            case "csv" -> "CSV";
            case "json" -> "JSON";
            case "html", "htm" -> "HTML page";
            case "png", "jpg", "jpeg", "gif", "webp" -> "image";
            case "" -> "file";
            default -> ext.toUpperCase(Locale.ROOT) + " file";
        };
    }
}
