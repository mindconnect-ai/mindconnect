package ai.mindconnect.chatui.ui;

import java.util.List;
import java.util.UUID;

/**
 * The links a host application contributes to the chat surface.
 *
 * <p>The chat itself only knows chat routes ({@code /chat/api/**}). Everything
 * that points somewhere else belongs to whoever embeds it: the admin UI wants
 * a way back to the agent, the working-memory and trace dialogs and a link to
 * the LLM config; a plain user-facing chat wants none of it and must not
 * render dead links into an admin it does not ship.
 *
 * <p>Hosts publish an implementation as a Spring bean. Without one,
 * {@link #NONE} applies and the chat renders only its own affordances.
 */
public interface ChatHostLinks {

    /** No host links — the default when nobody publishes a bean. */
    ChatHostLinks NONE = new ChatHostLinks() {};

    /** One host-contributed action in the message list's header. */
    record ToolLink(String id, String label, String icon, String url) {}

    /** Where "back" leads, or {@code null} for no back action. */
    default String backHref(UUID agentId, UUID sessionId) {
        return null;
    }

    /** Dialogs the host offers over the running conversation (memory, traces, …). */
    default List<ToolLink> sessionTools(UUID sessionId) {
        return List.of();
    }

    /** Href for an LLM config, or {@code null} to render the name as plain text. */
    default String llmConfigHref(String configName) {
        return null;
    }
}
