package ai.mindconnect.mail.imap;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Getting readable text out of a MIME message.
 *
 * <p>A mail is a tree of parts and only some of it is meant to be read: the
 * plain-text alternative when the sender provided one, the HTML one reduced to
 * its words when they did not, and nothing at all from the attachments. This
 * walks that tree once and collects both — the text, and the names of what was
 * attached, so the model can say "there is a PDF" without anybody parsing it.
 *
 * <p>The HTML reduction is deliberately crude: tags out, entities in, blank
 * lines collapsed. A model does not need a faithful rendering, and a real
 * HTML parser here would be a second dependency for a fallback path. Where
 * faithful text matters, the sender's own plain-text part is what gets used.
 */
public final class MailText {

    private MailText() { }

    /** What one message yields: its text, and what came with it. */
    /**
     * What a message carries: its plain text, its HTML when it has any, and
     * the names of its attachments.
     *
     * <p>{@code html} is the sender's markup, untouched and untrusted —
     * whoever renders it sanitises it first.
     */
    public record Extract(String text, String html, List<String> attachments) { }

    public static Extract of(Part part) throws MessagingException, IOException {
        StringBuilder plain = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<String> attachments = new ArrayList<>();
        walk(part, plain, html, attachments);
        String text = !plain.isEmpty() ? plain.toString() : stripHtml(html.toString());
        return new Extract(text.strip(), html.isEmpty() ? null : html.toString(),
                List.copyOf(attachments));
    }

    private static void walk(Part part, StringBuilder plain, StringBuilder html, List<String> attachments)
            throws MessagingException, IOException {
        String fileName = fileName(part);
        if (isAttachment(part)) {
            attachments.add(fileName != null ? fileName : "(unnamed attachment)");
            return;                                   // never read into the body
        }
        Object content;
        try {
            content = part.getContent();
        } catch (IOException | MessagingException e) {
            return;                                   // a part we cannot decode is not worth the whole message
        }
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                walk(child, plain, html, attachments);
            }
            return;
        }
        if (content instanceof String text) {
            if (part.isMimeType("text/html")) {
                append(html, text);
            } else if (part.isMimeType("text/*")) {
                append(plain, text);
            }
            return;
        }
        if (content instanceof Part nested) {
            walk(nested, plain, html, attachments);   // message/rfc822 — a forwarded mail
        }
    }

    /**
     * One attachment as a list shows it.
     *
     * @param index where it is among the message's attachments — how
     *              {@link #attachmentParts} is asked for it again
     * @param size  bytes, decoded; an estimate from the encoded size, or -1
     *              when the server did not say
     */
    public record Attached(int index, String name, String contentType, long size) { }

    /** One attachment's content. */
    public record AttachedFile(String name, String contentType, byte[] data) { }

    /**
     * The parts a person would call attachments, in the order the message
     * has them — the same ones {@link #of} names, so an index into this list
     * and a name in {@link Extract#attachments()} mean the same file.
     */
    public static List<Part> attachmentParts(Part part) throws MessagingException, IOException {
        List<Part> found = new ArrayList<>();
        collect(part, found);
        return found;
    }

    /** What {@link #attachmentParts} found, described. */
    public static List<Attached> describe(List<Part> parts) {
        List<Attached> out = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            String name = fileName(part);
            out.add(new Attached(i, name != null ? name : "(unnamed attachment)", contentType(part), size(part)));
        }
        return out;
    }

    /** One attachment's name, type and decoded bytes. */
    public static AttachedFile file(Part part) throws MessagingException, IOException {
        String name = fileName(part);
        try (java.io.InputStream in = part.getInputStream()) {
            return new AttachedFile(name != null ? name : "attachment", contentType(part), in.readAllBytes());
        }
    }

    private static void collect(Part part, List<Part> found) throws MessagingException, IOException {
        if (isAttachment(part)) {
            found.add(part);
            return;
        }
        Object content;
        try {
            content = part.getContent();
        } catch (IOException | MessagingException e) {
            return;
        }
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), found);
            }
        } else if (content instanceof Part nested) {
            collect(nested, found);
        }
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        String disposition = part.getDisposition();
        return Part.ATTACHMENT.equalsIgnoreCase(disposition)
                || (fileName(part) != null && !Part.INLINE.equalsIgnoreCase(disposition));
    }

    /** "application/pdf", without the parameters a Content-Type header carries. */
    private static String contentType(Part part) {
        try {
            String type = part.getContentType();
            if (type == null) return "application/octet-stream";
            int semi = type.indexOf(';');
            return (semi < 0 ? type : type.substring(0, semi)).strip().toLowerCase(java.util.Locale.ROOT);
        } catch (MessagingException e) {
            return "application/octet-stream";
        }
    }

    /** base64 is four characters for three bytes; the server counts the characters. */
    private static long size(Part part) {
        try {
            int encoded = part.getSize();
            if (encoded < 0) return -1;
            String[] encoding = part.getHeader("Content-Transfer-Encoding");
            boolean base64 = encoding != null && encoding.length > 0
                    && "base64".equalsIgnoreCase(encoding[0].strip());
            return base64 ? encoded * 3L / 4 : encoded;
        } catch (MessagingException e) {
            return -1;
        }
    }

    private static void append(StringBuilder target, String text) {
        if (text == null || text.isBlank()) return;
        if (!target.isEmpty()) target.append("\n\n");
        target.append(text);
    }

    /**
     * The file name as a person reads it. Many senders — Outlook among them —
     * write a name with an umlaut as an RFC 2047 word ({@code =?UTF-8?Q?M=C3=A4rz?=}),
     * which the part hands back as it stands.
     */
    private static String fileName(Part part) {
        try {
            String name = part.getFileName();
            if (name == null || !name.contains("=?")) return name;
            try {
                return jakarta.mail.internet.MimeUtility.decodeText(name);
            } catch (java.io.UnsupportedEncodingException e) {
                return name;
            }
        } catch (MessagingException e) {
            return null;
        }
    }

    /** Tags out, the common entities back to characters, runs of blank lines collapsed. */
    public static String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                .replaceAll("(?i)</(div|tr|li|h[1-6])\\s*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /** {@code text} cut to {@code maxChars}, with a line saying so; unchanged when it fits. */
    public static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) return text;
        return text.substring(0, maxChars)
                + "\n\n[… cut after " + maxChars + " characters of " + text.length()
                + ". Ask for this message again with a larger max_chars to see the rest.]";
    }
}
