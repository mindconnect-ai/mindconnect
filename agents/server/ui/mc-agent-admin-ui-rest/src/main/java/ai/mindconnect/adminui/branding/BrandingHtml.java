package ai.mindconnect.adminui.branding;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a resolved {@link Branding} to a static HTML shell — the SPA's
 * {@code index.html} and the {@code login.html} landing page.
 *
 * <p>The shells stay plain static files that open in a browser on their own;
 * branding is a pass over the text on the way out, not a template engine.
 * What it touches is anchored on markers the files carry on purpose — the
 * {@code <title>}, the {@code <html>} element, the login page's
 * {@code id="brand-title"} — so an anchor that is missing (a host app
 * shipping its own shell) leaves that part alone instead of corrupting the
 * document. The links go in ahead of {@code </head>}, last of all, so a
 * branding stylesheet wins over every shipped one.
 */
public final class BrandingHtml {

    /** Written on {@code <html>}: the theme the shell opens in. */
    public static final String THEME_ATTRIBUTE = "data-default-theme";
    /** Written on {@code <html>}: {@code off}, or the themes the picker may offer. */
    public static final String PICKER_ATTRIBUTE = "data-theme-picker";

    /** The document title. Non-greedy, so a page with a second one keeps it. */
    private static final Pattern TITLE = Pattern.compile("<title>.*?</title>", Pattern.DOTALL);
    /** The visible heading of the login page. */
    private static final Pattern BRAND_TITLE =
            Pattern.compile("(<h1\\s+id=\"brand-title\"[^>]*>).*?(</h1>)", Pattern.DOTALL);
    private static final Pattern HTML_OPEN = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_END = Pattern.compile("</head>", Pattern.CASE_INSENSITIVE);

    private BrandingHtml() {
    }

    /**
     * The shell with this request's title, tab icon, start theme, picker and
     * stylesheets. Returns {@code html} unchanged when nothing anchors.
     */
    public static String apply(String html, Branding branding) {
        return apply(html, branding, branding.documentTitle());
    }

    /**
     * The same, with the tab title spelled out — the login page says what the
     * page is <em>for</em> ("Sign in — …") ahead of who it belongs to.
     */
    public static String apply(String html, Branding branding, String documentTitle) {
        String out = replaceFirst(TITLE, html,
                quoted("<title>" + escapeText(documentTitle) + "</title>"));
        out = attribute(out, THEME_ATTRIBUTE, branding.theme());
        out = attribute(out, PICKER_ATTRIBUTE, branding.pickerAttribute());
        out = replaceFirst(BRAND_TITLE, out, "$1" + quoted(escapeText(branding.title())) + "$2");
        return insertIntoHead(out, headLinks(branding));
    }

    /** The tab icon and the branding stylesheets, in document order. */
    static String headLinks(Branding branding) {
        StringBuilder head = new StringBuilder();
        if (branding.favicon() != null) {
            head.append("    <link rel=\"icon\" href=\"").append(escapeAttribute(branding.favicon())).append("\">\n");
        }
        for (String stylesheet : branding.stylesheets()) {
            head.append("    <link rel=\"stylesheet\" href=\"").append(escapeAttribute(stylesheet)).append("\">\n");
        }
        if (head.isEmpty()) {
            return "";
        }
        return "    <!-- Branding (mindconnect.branding): last in <head>, so these win. -->\n" + head;
    }

    /**
     * Sets an attribute on the {@code <html>} element, replacing the value
     * when the shell already carries it and adding it when it does not — the
     * static file is then free to declare the ones that document themselves
     * and stay silent about the rest.
     */
    static String attribute(String html, String name, String value) {
        Matcher open = HTML_OPEN.matcher(html);
        if (!open.find()) return html;
        String tag = open.group();
        String attribute = name + "=\"" + escapeAttribute(value == null ? "" : value) + "\"";
        Pattern existing = Pattern.compile("\\s" + Pattern.quote(name) + "=\"[^\"]*\"");
        Matcher inTag = existing.matcher(tag);
        String replaced = inTag.find()
                ? inTag.replaceFirst(quoted(" " + attribute))
                : tag.substring(0, tag.length() - 1).stripTrailing() + " " + attribute + ">";
        return html.substring(0, open.start()) + replaced + html.substring(open.end());
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
