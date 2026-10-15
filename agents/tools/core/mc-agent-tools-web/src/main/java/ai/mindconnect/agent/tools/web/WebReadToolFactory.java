package ai.mindconnect.agent.tools.web;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import okhttp3.OkHttpClient;

public final class WebReadToolFactory implements ToolFactory {

    /**
     * Module-owned client with bounded timeouts (see
     * {@link WebToolsHttpClient}). We deliberately do NOT take an
     * {@link OkHttpClient} from the {@link ToolEnvironment}: the host's
     * primary client is tuned for SSE streams (read-timeout=0) and would
     * let a hanging fetch stall the whole agent.
     */
    private final OkHttpClient httpClient = WebToolsHttpClient.get();

    @Override public String name() { return "web_read"; }

    @Override public String group() { return "web"; }

    @Override public void bind(ToolEnvironment env) {
        // Nothing to wire — the HTTP client is self-contained.
    }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new WebReadTool(httpClient);
    }
}
