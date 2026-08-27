package ai.mindconnect.adminui.assembler.session;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Shared static helpers used by every session-related UI assembler
 * (chat, working memory, traces) <i>and</i> by the cross-package
 * UI components in {@code ai.mindconnect.adminui.ui.component}.
 * Kept tiny on purpose — anything that grows here either belongs in
 * a dedicated assembler or in a domain helper.
 *
 * <p>Made public (with public members) so the components package can
 * share the same Jackson instance and time formatter without each
 * component carrying its own copy.
 */
public final class SessionUiCommons {

    /**
     * Single Jackson instance shared across the assemblers. None of them
     * mutate it, and Jackson's {@link ObjectMapper} is documented as
     * thread-safe for read/write of normal records.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** Time formatter used in conversation, memory and trace headers. */
    public static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private SessionUiCommons() {}

    /**
     * Squashes whitespace and truncates {@code s} to 120 chars (with an
     * ellipsis) so it fits one line in a list-item description.
     */
    public static String previewOf(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "…";
    }

    /** Wraps a body in a markdown code-fence with optional language hint. */
    public static String codeBlock(String body, String lang) {
        String fence = lang != null ? "```" + lang : "```";
        return fence + "\n" + (body == null ? "" : body) + "\n```";
    }
}
