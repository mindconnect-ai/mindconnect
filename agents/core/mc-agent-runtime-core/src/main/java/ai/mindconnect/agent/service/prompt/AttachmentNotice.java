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
 * Tells the model what happened to the chat's attachments — in the user's
 * turn, where the question is, not only in the system prompt where it is a
 * footnote.
 *
 * <p>Two events, both recorded on the first user message after them, in
 * its metadata: {@link #ATTACHMENTS} names files attached since the last
 * turn, {@link #DETACHED} names files removed since. The record is never
 * rewritten; read in order it says what the model has been told so far
 * ({@link #announced}), and the next turn announces only the difference to
 * the session — a file attached again after a removal is announced again.
 *
 * <p>The wording is rendered from that metadata when the request is built
 * ({@code MessageToLlmMessageMapper}): the stored text stays what the user
 * typed, the chat UI shows the same metadata as a chip. An attach notice
 * names only files still attached; a detach notice names only files still
 * gone. Origin in the record, presentation at the consumer.
 */
public final class AttachmentNotice {

    /** Message metadata key: the files this user message announced as attached. */
    public static final String ATTACHMENTS = "attachments";

    /** Message metadata key: the files this user message announced as removed. */
    public static final String DETACHED = "detached";

    private AttachmentNotice() {
    }

    /** What the model has been told is attached, after reading {@code history} in order. */
    public static Set<String> announced(List<Message> history) {
        Set<String> announced = new LinkedHashSet<>();
        for (Message m : history) {
            if (m.senderType() != ParticipantType.USER) continue;
            announced.addAll(names(m, ATTACHMENTS));
            names(m, DETACHED).forEach(announced::remove);
        }
        return announced;
    }

    /** Files attached to the session that the model has not been told about, in attach order. */
    public static List<String> unannounced(AgentSession session, List<Message> history) {
        if (session == null || session.attachedFiles().isEmpty()) return List.of();
        Set<String> announced = announced(history);
        List<String> fresh = new ArrayList<>();
        for (String file : session.attachedFiles()) {
            if (!announced.contains(file)) fresh.add(file);
        }
        return fresh;
    }

    /** Files the model has been told about that are no longer attached — removed since the last turn. */
    public static List<String> unannouncedRemovals(AgentSession session, List<Message> history) {
        Set<String> live = session == null ? Set.of() : new LinkedHashSet<>(session.attachedFiles());
        List<String> gone = new ArrayList<>();
        for (String file : announced(history)) {
            if (!live.contains(file)) gone.add(file);
        }
        return gone;
    }

    /** Metadata for the message that announces these changes; empty when there are none. */
    public static Map<String, Object> metadata(List<String> attached, List<String> detached) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (!attached.isEmpty()) out.put(ATTACHMENTS, List.copyOf(attached));
        if (!detached.isEmpty()) out.put(DETACHED, List.copyOf(detached));
        return Map.copyOf(out);
    }

    /** The attachment names a stored message announced as attached; empty when none. */
    public static List<String> announcedBy(Message message) {
        return names(message, ATTACHMENTS);
    }

    /** The attachment names a stored message announced as removed; empty when none. */
    public static List<String> detachedBy(Message message) {
        return names(message, DETACHED);
    }

    /**
     * The line that goes ahead of the user's text for newly attached files —
     * what was attached, what kind of file it is, and the one instruction
     * that matters: the content is reached through {@code vector_search},
     * never through a path. Marked as a system note so the model does not
     * take it for something the user said.
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

    /** The line for files removed from the chat: their content is gone, and gone means gone. */
    public static String removalNotice(List<String> files) {
        if (files.isEmpty()) return "";
        return "[System note — removed from this chat: " + String.join(", ", files)
                + " — the content is no longer available. If asked about it, say that the file was removed; "
                + "do not search for it and do not look it up elsewhere.]";
    }

    /**
     * The user's text as the model reads it: the notices ahead of it for
     * what this message announced and what still holds — attached files that
     * are still attached, removed files that are still gone — and the text
     * alone otherwise.
     */
    public static String forModel(Message message, AgentSession session) {
        Set<String> live = session == null ? Set.of() : new LinkedHashSet<>(session.attachedFiles());
        List<String> attached = announcedBy(message).stream().filter(live::contains).toList();
        List<String> removed = detachedBy(message).stream().filter(f -> !live.contains(f)).toList();
        StringBuilder out = new StringBuilder();
        if (!removed.isEmpty()) out.append(removalNotice(removed)).append("\n\n");
        if (!attached.isEmpty()) out.append(notice(attached)).append("\n\n");
        return out.append(message.content()).toString();
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

    private static List<String> names(Message message, String key) {
        if (message == null || message.metadata() == null) return List.of();
        Object names = message.metadata().get(key);
        if (!(names instanceof Collection<?> c) || c.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        c.forEach(n -> out.add(String.valueOf(n)));
        return out;
    }
}
