package ai.mindconnect.mail;

/**
 * A message's body in both the shapes a sender may have written it.
 *
 * <p>Two fields rather than one, because the two readers want different
 * things. A model wants {@link #text}: words, no markup, cheap. A person
 * wants {@link #html} when the sender wrote HTML — a newsletter reduced to
 * its words is a wall of link targets, which is exactly what this record
 * exists to stop.
 *
 * @param html the sender's HTML, verbatim and <b>untrusted</b>. Null when the
 *             message was plain text. Whoever displays it sanitises it first
 *             and never puts it into the page's own document
 * @param text the plain part where there was one, else the HTML reduced to
 *             its words. Never null, possibly empty
 */
public record MailBody(String html, String text) {

    public MailBody {
        if (html != null && html.isBlank()) html = null;
        if (text == null) text = "";
    }

    public static MailBody plain(String text) {
        return new MailBody(null, text);
    }

    public boolean isHtml() {
        return html != null;
    }
}
