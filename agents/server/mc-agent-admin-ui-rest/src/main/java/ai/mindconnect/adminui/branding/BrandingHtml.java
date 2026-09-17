package ai.mindconnect.adminui.branding;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@link BrandingProperties} to a static HTML shell — the SPA's
 * {@code index.html} and the {@code login.html} landing page.
 *
 * <p>The shells stay plain static files that open in a browser on their own;
 * branding is a pass over the text on the way out, not a template engine.
 * What it touches is anchored on markers the files carry on purpose — the
 * {@code <title>}, {@code data-default-theme} on {@code <html>}, the login
 * page's {@code id="brand-title"} — so an anchor that is missing (a host app
 * shipping its own shell) simply leaves that part alone instead of corrupting
 * the document. The links go in ahead of {@code </head>}, last of all, so a
 * branding stylesheet wins over every shipped one.
 */
public final class BrandingHtml {

    /** The document title. Non-greedy, so a page with a second one keeps it. */
    private static final Pattern TITLE = Pattern.compile("<title>.*?</title>", Pattern.DOTALL);
    /** The theme the shell's pre-paint script falls back to. */
    private static final Pattern DEFAULT_THEME = Pattern.compile("(<html[^>]*\\sdata-default-theme=\")[^\"]*(\")");
    /** The visible heading of the login page. */
    private static final Pattern BRAND_TITLE =
            Pattern.compile("(<h1\\s+id=\"brand-title\"[^>]*>).*?(</h1>)", Pattern.DOTALL);
    private static final Pattern HEAD_END = Pattern.compile("</head>", Pattern.CASE_INSENSITIVE);

    private BrandingHtml() {
    }

    /**
     * The shell with this installation's title, tab icon, start theme and
     * stylesheets. Returns {@code html} unchanged when nothing anchors.
     */
    public static String apply(String html, BrandingProperties branding) {
        return apply(html, branding, branding.getDocumentTitle());
    }

    /**
     * The same, with the tab title spelled out — the login page says what the
     * page is <em>for</em> ("Sign in — …") ahead of who it belongs to.
     */
    public static String apply(String html, BrandingProperties branding, String documentTitle) {
        String out = replaceFirst(TITLE, html,
                quoted("<title>" + escapeText(documentTitle) + "</title>"));
        out = replaceFirst(DEFAULT_THEME, out, "$1" + quoted(escapeAttribute(branding.getTheme())) + "$2");
        out = replaceFirst(BRAND_TITLE, out, "$1" + quoted(escapeText(branding.getTitle())) + "$2");
        return insertIntoHead(out, headLinks(branding));
    }

    /** The tab icon and the branding stylesheets, in document order. */
    static String headLinks(BrandingProperties branding) {
        StringBuilder head = new StringBuilder();
        if (branding.getFavicon() != null) {
            head.append("    <link rel=\"icon\" href=\"").append(escapeAttribute(branding.getFavicon())).append("\">\n");
        }
        for (String stylesheet : branding.stylesheetUrls()) {
            head.append("    <link rel=\"stylesheet\" href=\"").append(escapeAttribute(stylesheet)).append("\">\n");
        }
        if (head.isEmpty()) {
            return "";
        }
        return "    <!-- Branding (mindconnect.branding): last in <head>, so these win. -->\n" + head;
    }

    private static String insertIntoHead(String html, String snippet) {
        if (snippet.isEmpty()) {
            return html;
        }
        Matcher end = HEAD_END.matcher(html);
        if (!end.find()) {
            return html;
        }
        return html.substring(0, end.start()) + snippet + html.substring(end.start());
    }

    private static String replaceFirst(Pattern pattern, String html, String replacement) {
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher.replaceFirst(replacement) : html;
    }

    /** A replacement group reference ({@code $1}) must stay live, the value around it must not. */
    private static String quoted(String value) {
        return Matcher.quoteReplacement(value);
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttribute(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }
}
