package ai.mindconnect.agent.tools.web;

import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.document.RelevantExcerpts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final MediaType JSON = MediaType.get("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final OkHttpClient httpClient;

    public WebSearchTool(String apiKey, OkHttpClient httpClient) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "ALWAYS use this tool first when you need information from the web. " +
               "Searches the web using Tavily and returns, per result, the title, the URL, and " +
               "the passages of the page that bear on your query — Tavily fetches the pages, so " +
               "sites that refuse direct access are usually still readable here.\n" +
               "For most questions this is enough on its own: read the returned passages before " +
               "reaching for web_read. Use web_read only when you need a part of a page this " +
               "result did not cover.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "The search query"
                        ),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum number of results to return (default 5, max 10)"
                        ),
                        "include_content", Map.of(
                                "type", "boolean",
                                "default", true,
                                "description", "Return the relevant passages of each result page, "
                                        + "not just the snippet. Leave on unless you only need a "
                                        + "list of URLs — the passages are what usually make a "
                                        + "follow-up web_read unnecessary."
                        )
                ),
                "required", new String[]{"query"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = (String) arguments.get("query");
        if (query == null || query.isBlank()) return "Error: query is required";

        log.info("web_search query=\"{}\"", query);
        int maxResults = 5;
        Object maxResultsArg = arguments.get("max_results");
        if (maxResultsArg instanceof Number n) maxResults = Math.min(n.intValue(), 10);

        // Tavily already fetched and cleaned these pages. Taking its content
        // costs one request; re-fetching the same pages ourselves costs a
        // sub-agent session each, and fails outright on the roughly half of
        // sites that refuse an automated GET.
        boolean includeContent = !Boolean.FALSE.equals(arguments.get("include_content"));

        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "max_results", maxResults,
                    "include_answer", true,
                    "include_raw_content", includeContent
            ));

            Request request = new Request.Builder()
                    .url(TAVILY_URL)
                    .post(RequestBody.create(body, JSON))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "Error: Tavily API returned HTTP " + response.code();
                }
                String responseBody = response.body().string();
                return formatResults(responseBody, query);
            }
        } catch (Exception e) {
            log.error("WebSearchTool error: {}", e.getMessage());
            return "Error performing web search: " + e.getMessage();
        }
    }

    /**
     * Per-result budget for page content. Five results at this size sit in the
     * same order of magnitude as the snippet-only output this replaces, while
     * carrying the passages that previously cost one page fetch each.
     */
    private static final int CONTENT_CHARS_PER_RESULT = 1_200;

    private String formatResults(String json, String query) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode results = root.get("results");
        if (results == null || results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        String answer = text(root, "answer");
        if (!answer.isBlank()) {
            // Generated by the search provider, not read off a page: useful as a
            // lead, never as a citation. Saying so here is cheaper than repairing
            // an answer that cites it as if it were a source.
            sb.append("Provider answer (generated — treat as a lead, cite the sources below): ")
              .append(answer).append("\n\n");
        }

        int i = 1;
        for (JsonNode r : results) {
            sb.append(i++).append(". **").append(text(r, "title")).append("**\n");
            sb.append("   URL: ").append(text(r, "url")).append("\n");

            String raw = text(r, "raw_content");
            if (raw.isBlank()) {
                sb.append("   ").append(text(r, "content")).append("\n");
            } else {
                // The snippet is itself an extract of this page, so printing it
                // beside the selected passages pays twice for the same text —
                // and on a small context window that second payment is the one
                // that pushes the answer out.
                sb.append("   Relevant passages from this page:\n")
                  .append(block(raw, query))
                  .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * The selected passages, indented under their result. Truncation happens
     * after indenting: the budget is what the model has to read, and the three
     * leading spaces on every line are part of that.
     */
    private static String block(String raw, String query) {
        String indented = RelevantExcerpts.select(clean(raw), query, CONTENT_CHARS_PER_RESULT)
                .strip().lines()
                .map(line -> "   " + line)
                .collect(java.util.stream.Collectors.joining("\n"));
        return indented.length() <= CONTENT_CHARS_PER_RESULT
                ? indented
                : indented.substring(0, CONTENT_CHARS_PER_RESULT) + "\n   […]";
    }

    /**
     * Provider content arrives as page Markdown, images and all. A reader has no
     * use for an image, and a logo's URL or a tracking link can outweigh the
     * price beside it — both in the character budget and in the ranking. Dropping
     * them is the cheapest density gain available here.
     */
    private static String clean(String markdown) {
        return markdown
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("\\n{3,}", "\\n\\n");
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : "";
    }
}
