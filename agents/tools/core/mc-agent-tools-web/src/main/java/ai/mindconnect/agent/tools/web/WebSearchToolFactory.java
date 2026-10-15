package ai.mindconnect.agent.tools.web;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebSearchToolFactory implements ToolFactory {

    private static final Logger log = LoggerFactory.getLogger(WebSearchToolFactory.class);

    /** Environment key for the Tavily API key. */
    public static final String TAVILY_API_KEY = "tavilyApiKey";

    /**
     * Module-owned client with bounded timeouts (see
     * {@link WebToolsHttpClient}) — same reasoning as in
     * {@link WebReadToolFactory}.
     */
    private final OkHttpClient httpClient = WebToolsHttpClient.get();
    private String apiKey;

    @Override public String name() { return "web_search"; }

    @Override public String group() { return "web"; }

    @Override public void bind(ToolEnvironment env) {
        this.apiKey = env.getString(TAVILY_API_KEY).orElse(null);
    }

    @Override public boolean isAvailable() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Tool 'web_search' is configured but '{}' is not set — tool will be unavailable",
                    TAVILY_API_KEY);
            return false;
        }
        return true;
    }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new WebSearchTool(apiKey, httpClient);
    }
}
