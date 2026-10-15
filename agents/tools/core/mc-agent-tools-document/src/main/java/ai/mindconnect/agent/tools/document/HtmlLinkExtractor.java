package ai.mindconnect.agent.tools.document;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts a focused list of {@code <a href>} links from an HTML page so the LLM
 * has a verbatim, machine-checkable source of URLs (rather than re-deriving them
 * from prose).
 * <p>
 * Strategy:
 * <ul>
 *   <li>Prefer {@code <main>} / {@code <article>} / {@code [role=main]} as the
 *       extraction root; fall back to {@code <body>}.</li>
 *   <li>Strip navigation/header/footer/aside before scanning, matching what
 *       {@link HtmlToMarkdown} drops.</li>
 *   <li>Use anchor text when present; if the only child is an {@code <img>},
 *       use its {@code alt} attribute and prefix with {@code (image link)}.</li>
 *   <li>Skip empty/whitespace-only anchors and trivial fragments
 *       ({@code #}, {@code mailto:}, {@code javascript:}).</li>
 *   <li>Dedupe by absolute href; keep insertion order.</li>
 *   <li>Cap at {@link #MAX_LINKS} entries.</li>
 * </ul>
 */
public final class HtmlLinkExtractor {

    public static final int MAX_LINKS = 50;
    private static final int MAX_TEXT_CHARS = 120;

    private HtmlLinkExtractor() {}

    /**
     * Returns a Markdown-formatted block of links, or "" if no usable links were found.
     * The block starts with a blank line and a heading, so it can be appended to other
     * Markdown content directly.
     */
    public static String extract(Document doc) {
        Element root = pickRoot(doc);
        if (root == null) return "";

        // Drop the same boilerplate as HtmlToMarkdown so we don't surface nav/footer links.
        // Operate on a clone so we don't mutate the caller's document.
        Element scoped = root.clone();
        scoped.select("nav, footer, header, aside, script, style, noscript, iframe, form, " +
                      "button, svg").remove();

        Elements anchors = scoped.select("a[href]");
        if (anchors.isEmpty()) return "";

        // Preserve insertion order; dedupe by absolute URL.
        Map<String, String> linkLines = new LinkedHashMap<>();
        for (Element a : anchors) {
            String href = a.absUrl("href");
            if (href.isEmpty()) href = a.attr("href");
            if (!isUsableHref(href)) continue;

            String text = anchorText(a);
            if (text.isEmpty()) continue;

            String line = "- [" + truncate(text) + "](" + href + ")";
            linkLines.putIfAbsent(href, line);
            if (linkLines.size() >= MAX_LINKS) break;
        }

        if (linkLines.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Links found on this page\n");
        sb.append("(Verbatim `<a href>` values from the page body. Use these directly when citing links — do not modify them.)\n");
        for (String line : linkLines.values()) {
            sb.append(line).append('\n');
        }
        if (anchors.size() > linkLines.size()) {
            sb.append("\n_…showing ").append(linkLines.size())
              .append(" of ").append(anchors.size()).append(" anchors found._\n");
        }
        return sb.toString();
    }

    private static Element pickRoot(Document doc) {
        Element main = doc.selectFirst("main, article, [role=main]");
        if (main != null) return main;
        return doc.body();
    }

    private static boolean isUsableHref(String href) {
        if (href == null || href.isBlank()) return false;
        String h = href.trim();
        if (h.startsWith("#") || h.startsWith("javascript:") || h.startsWith("mailto:")
                || h.startsWith("tel:") || h.startsWith("data:")) return false;
        // Resolved absolute URLs only — relative leftovers are ambiguous to the LLM.
        try {
            URI u = URI.create(h);
            String scheme = u.getScheme();
            return scheme != null && (scheme.equals("http") || scheme.equals("https"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the anchor's effective text. If the anchor has no visible text but
     * wraps an {@code <img>}, fall back to the image's {@code alt} attribute,
     * marked with {@code (image link)} so the LLM doesn't mistake the URL for
     * an image asset link.
     */
    private static String anchorText(Element a) {
        String txt = a.text().trim();
        if (!txt.isEmpty()) return txt;

        Element img = a.selectFirst("img[alt]");
        if (img != null) {
            String alt = img.attr("alt").trim();
            if (!alt.isEmpty()) return "(image link) " + alt;
        }
        // Last resort — title attribute of the anchor itself
        String title = a.attr("title").trim();
        if (!title.isEmpty()) return title;
        return "";
    }

    private static String truncate(String text) {
        String single = text.replaceAll("\\s+", " ");
        if (single.length() > MAX_TEXT_CHARS) {
            return single.substring(0, MAX_TEXT_CHARS - 1) + "…";
        }
        return single;
    }

    /** Convenience for tests. */
    public static List<String> extractAsList(Document doc) {
        String block = extract(doc);
        if (block.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : block.split("\n")) {
            if (line.startsWith("- [")) out.add(line);
        }
        return out;
    }
}
