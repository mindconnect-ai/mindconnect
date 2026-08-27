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
        return "Fetches and reads the full content of a specific URL as text. " +
               "Supports HTML pages, PDFs, Word documents, and other file types. " +
               "Use this AFTER web_search to read the full content of a result. " +
               "Do NOT use this to search — use web_search first to find relevant URLs.\n" +
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

        log.info("web_read url={} createLinkList={}", url, createLinkList);
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
                    return "Error: HTTP " + response.code() + " for " + url;
                }
                String contentType = response.header("Content-Type", "");
                if (contentType != null && contentType.contains("text/html")) {
                    // HTML path: let OkHttp decode bytes → String using the
                    // charset declared in Content-Type, then hand it to Jsoup
                    // (which wants a String for relative-link resolution).
                    String html = response.body().string();
                    return DocumentParser.parseString(url, contentType, html, null, createLinkList);
                }
                // Binary or non-HTML text (PDF, DOCX, plain text, …): pass the
                // raw byte stream straight to Tika via parseUrl so the document
                // is actually extracted rather than mojibake-decoded as text.
                return DocumentParser.parseUrl(url, contentType,
                        response.body().byteStream(), null, createLinkList);
            }
        } catch (Exception e) {
            log.warn("WebReadTool failed for {}: {}", url, e.getMessage());
            return "Error reading page: " + e.getMessage();
        }
    }
}
