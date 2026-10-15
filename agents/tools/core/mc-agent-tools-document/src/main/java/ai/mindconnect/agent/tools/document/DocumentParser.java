package ai.mindconnect.agent.tools.document;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DocumentParser {

    public static final int MAX_CHARS = 20_000;

    private static final Tika TIKA = new Tika();

    /**
     * Parse already-decompressed text content fetched from a URL.
     * Prefer this over {@link #parseUrl(String, String, InputStream, String, boolean)} for HTTP responses
     * so that OkHttp's transparent gzip decompression is in effect.
     *
     * <p>{@code createLinkList=true} appends a trailing {@code ## Links found on this page}
     * block listing every usable {@code <a href>} from the body — useful when the
     * caller needs verbatim URLs for citation. Default ({@code false}) returns only
     * the body Markdown with inline {@code [text](url)} links.
     */
    public static String parseString(String url, String contentType, String body, String title,
                                     boolean createLinkList) throws IOException, TikaException {
        return parseString(url, contentType, body, title, createLinkList, null);
    }

    /**
     * As above, but when {@code query} is non-blank the body is reduced to the
     * passages that bear on it (see {@link RelevantExcerpts}) instead of being
     * cut at {@link #MAX_CHARS}. A head-truncated page drops whatever sits in
     * the last third — often exactly the figure the caller asked for.
     */
    public static String parseString(String url, String contentType, String body, String title,
                                     boolean createLinkList, String query) throws IOException, TikaException {
        if (contentType != null && contentType.contains("text/html")) {
            Document doc = Jsoup.parse(body, url);
            return renderHtml(url, title, doc, createLinkList, query);
        }
        return "URL: " + url + "\n\n" + reduce(body, query);
    }

    /** Backwards-compatible overload: no link list (current default behaviour). */
    public static String parseString(String url, String contentType, String body, String title) throws IOException, TikaException {
        return parseString(url, contentType, body, title, false);
    }

    public static String parseUrl(String url, String contentType, InputStream body, String title,
                                  boolean createLinkList) throws IOException, TikaException {
        return parseUrl(url, contentType, body, title, createLinkList, null);
    }

    /** As above, with relevance filtering when {@code query} is non-blank. */
    public static String parseUrl(String url, String contentType, InputStream body, String title,
                                  boolean createLinkList, String query) throws IOException, TikaException {
        if (contentType != null && contentType.contains("text/html")) {
            String html = new String(body.readAllBytes());
            Document doc = Jsoup.parse(html, url);
            return renderHtml(url, title, doc, createLinkList, query);
        }
        return "URL: " + url + "\n\n" + reduce(TIKA.parseToString(body), query);
    }

    /** Backwards-compatible overload: no link list. */
    public static String parseUrl(String url, String contentType, InputStream body, String title) throws IOException, TikaException {
        return parseUrl(url, contentType, body, title, false);
    }

    /**
     * Builds the Markdown body. Inline {@code [text](url)} links inside
     * the body already give the agent everything it needs to drill into
     * specifics — so by default a trailing footer block of raw URLs is
     * omitted as redundant noise.
     *
     * <p>When {@code createLinkList} is {@code true} the
     * {@link HtmlLinkExtractor#extract(Document)} block is appended after
     * the body. Callers opt in only when they need a verbatim URL list
     * (e.g. for citation, or when downstream code wants to follow links
     * mechanically rather than via the LLM).
     */
    private static String renderHtml(String url, String title, Document doc,
                                     boolean createLinkList, String query) {
        String resolvedTitle = title != null ? title : doc.title();
        String markdown = HtmlToMarkdown.convert(doc);
        String bodyText = reduce(markdown, query);
        String head = "URL: " + url + "\nTitle: " + resolvedTitle + "\n\n" + bodyText;
        if (!createLinkList) return head;
        String linkBlock = HtmlLinkExtractor.extract(doc);
        return linkBlock.isEmpty() ? head : head + linkBlock;
    }

    public static String parseFile(Path file) throws IOException, TikaException {
        String contentType = Files.probeContentType(file);
        try (InputStream in = Files.newInputStream(file)) {
            if (contentType != null && contentType.contains("text/html")) {
                Document doc = Jsoup.parse(in, null, file.toUri().toString());
                return truncate(HtmlToMarkdown.convert(doc));
            } else if (contentType == null || contentType.startsWith("text/")) {
                return truncate(Files.readString(file));
            } else {
                return truncate(TIKA.parseToString(in));
            }
        }
    }

    /**
     * Cuts {@code text} down to size: by relevance when a query is given, by
     * position otherwise. The query path has the smaller budget on purpose —
     * selected passages carry far more answer per character than a prefix does.
     */
    private static String reduce(String text, String query) {
        if (query == null || query.isBlank()) return truncate(text);
        return RelevantExcerpts.select(text, query);
    }

    public static String truncate(String text) {
        return truncate(text, MAX_CHARS);
    }

    public static String truncate(String text, int limit) {
        if (text.length() > limit) {
            return text.substring(0, limit) + "\n\n[truncated after " + limit + " chars]";
        }
        return text;
    }
}
