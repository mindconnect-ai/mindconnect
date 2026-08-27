package ai.mindconnect.agent.tools.web;

import ai.mindconnect.agent.tool.Tool;
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
               "Searches the web using Tavily and returns a list of relevant results with titles, URLs, and content snippets. " +
               "Use web_read afterwards to fetch the full content of a specific result URL.";
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

        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "max_results", maxResults,
                    "include_answer", false,
                    "include_raw_content", false
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
                return formatResults(responseBody);
            }
        } catch (Exception e) {
            log.error("WebSearchTool error: {}", e.getMessage());
            return "Error performing web search: " + e.getMessage();
        }
    }

    private String formatResults(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        JsonNode results = root.get("results");
        if (results == null || results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode r : results) {
            sb.append(i++).append(". **").append(text(r, "title")).append("**\n");
            sb.append("   URL: ").append(text(r, "url")).append("\n");
            sb.append("   ").append(text(r, "content")).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : "";
    }
}
