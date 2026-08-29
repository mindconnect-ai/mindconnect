package ai.mindconnect.adminui.chat;

import ai.mindconnect.chatui.ui.ChatHostLinks;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * What the admin UI adds to the chat surface: the way back to the agent, the
 * inspection dialogs (working memory, traces, todos, workspace) and the link
 * to the session's LLM config.
 *
 * <p>These all point at {@code /admin/api/**}, which is exactly why they live
 * here and not in the chat module — a standalone chat app publishes no such
 * bean and renders none of them.
 */
@Component
public class AdminChatHostLinks implements ChatHostLinks {

    @Override
    public String backHref(UUID agentId, UUID sessionId) {
        return "/admin/agents/" + agentId + "?section=sessions&row=" + sessionId;
    }

    @Override
    public List<ToolLink> sessionTools(UUID sessionId) {
        String base = "/admin/api/sessions/" + sessionId;
        return List.of(
                new ToolLink("memory",    "Working Memory", "chart",  base + "/memory?dialog=true"),
                new ToolLink("traces",    "Traces",         "list",   base + "/traces?dialog=true"),
                new ToolLink("todos",     "Todos",          "check",  base + "/todos?dialog=true"),
                new ToolLink("workspace", "Workspace",      "folder", base + "/workspace?dialog=true"));
    }

    @Override
    public String llmConfigHref(String configName) {
        return "/admin/api/llm-configs/byName/" + configName;
    }
}
