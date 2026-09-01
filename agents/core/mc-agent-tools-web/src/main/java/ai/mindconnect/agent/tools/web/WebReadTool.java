package ai.mindconnect.agent.tools.web;

import ai.mindconnect.agent.tools.document.DocumentParser;
import ai.mindconnect.agent.tool.Tool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class WebReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebReadTool.class);
    private static final int TIMEOUT_MS = 15_000;

    private final OkHttpClient httpClient;

    public WebReadTool(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "web_read";
    }

    @Override
    public String description() {
        return "Fetches and reads the content of a specific URL as text. " +
               "Supports HTML pages, PDFs, Word documents, and other file types. " +
               "Use this AFTER web_search to read the full content of a result. " +
               "Do NOT use this to search — use web_search first to find relevant URLs.\n" +
               "ALWAYS pass `query` describing what you need from the page: the tool then " +
               "returns only the passages that bear on it, instead of the first few thousand " +
               "characters. On a long page the answer often sits near the end, so reading " +
               "without a query can silently cut it off.\n" +
               "HTML pages are returned as Markdown with inline `[text](url)` links — " +
               "use those directly when citing.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "The URL of the page or document to read"
                        ),
                        "query", Map.of(
                                "type", "string",
                                "description", "What you want to find on this page, in a few "
                                        + "words — e.g. \"price and availability\" or "
                                        + "\"system requirements\". Only the passages matching "
                                        + "it are returned, which keeps a long page from "
                                        + "flooding (and overflowing) the context. Omit only "
                                        + "when you genuinely need the whole page."
                        ),
                        "createLinkList", Map.of(
                                "type", "boolean",
                                "default", false,
                                "description", "Leave unset (false) for normal reading — "
                                        + "the inline `[text](url)` Markdown links in the body "
                                        + "are sufficient for citing. Only set true when the caller "
                                        + "explicitly needs a verbatim machine-readable list of every "
                                        + "`<a href>` on the page (e.g. crawling all items from a "
                                        + "listing page)."
                        )
                ),
                "required", new String[]{"url"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String url = (String) arguments.get("url");
        if (url == null || url.isBlank()) return "Error: url is required";
        boolean createLinkList = Boolean.TRUE.equals(arguments.get("createLinkList"));
        String query = arguments.get("query") instanceof String q && !q.isBlank() ? q : null;

        log.info("web_read url={} query={} createLinkList={}", url, query, createLinkList);
        try {
            // Do NOT set Accept-Encoding manually — OkHttp adds it automatically and
            // decompresses the response transparently. Setting it manually bypasses that
            // and hands raw compressed bytes to the body reader.
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "de-CH,de;q=0.9,en;q=0.8")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return httpError(response.code(), url);
                }
                String contentType = response.header("Content-Type", "");
                if (contentType != null && contentType.contains("text/html")) {
                    // HTML path: let OkHttp decode bytes → String using the
                    // charset declared in Content-Type, then hand it to Jsoup
                    // (which wants a String for relative-link resolution).
                    String html = response.body().string();
                    return DocumentParser.parseString(url, contentType, html, null, createLinkList, query);
                }
                // Binary or non-HTML text (PDF, DOCX, plain text, …): pass the
                // raw byte stream straight to Tika via parseUrl so the document
                // is actually extracted rather than mojibake-decoded as text.
                return DocumentParser.parseUrl(url, contentType,
                        response.body().byteStream(), null, createLinkList, query);
            }
        } catch (Exception e) {
            log.warn("WebReadTool failed for {}: {}", url, e.getMessage());
            return "Error reading page: " + e.getMessage()
                    + "\nThe fetch itself failed — the URL was not read. Retrying the identical "
                    + "request will fail the same way. Use a different source.";
        }
    }

    /**
     * Turns an HTTP status into a message that says what to do next.
     * <p>
     * A bare "Error: HTTP 403" leaves the model to invent a recovery, and what it
     * invents is usually the same URL under /en/ or /it/ — which blocks identically,
     * costing another full round for nothing. Naming the dead end closes it.
     */
    private static String httpError(int code, String url) {
        String head = "Error: HTTP " + code + " for " + url;
        return head + switch (code) {
            case 401, 403 -> "\nThis site refuses automated access. Language and region "
                    + "variants of the same domain (/de/, /en/, /it/) and other paths on it "
                    + "refuse it too — do NOT retry them. Either read this exact URL with a "
                    + "JavaScript-capable browser reader if you have one, or pick a different "
                    + "source from your search results.";
            case 404, 410 -> "\nThe page does not exist. Do NOT guess a corrected URL — use "
                    + "only URLs that appeared verbatim in a search result.";
            case 429 -> "\nRate limited. Retrying now will fail again, and so will other "
                    + "paths on this domain. Use a different source.";
            default -> code >= 500
                    ? "\nThe server failed. One retry may work; if it fails again, use a "
                      + "different source."
                    : "\nThe page could not be read. Use a different source.";
        };
    }
}
