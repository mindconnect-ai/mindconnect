package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiLink;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The "Info" tab of a chat page — a read-only key/value list of
 * session metadata (id, agent, status, user, started-at, title) plus
 * a "Back to Agent" link.
 *
 * <p>Static by construction: the panel never updates incrementally.
 * It only exposes {@link #render()}; there are no patch operations.
 */
public final class SessionInfoComponent implements UiComponent {

    private static final DateTimeFormatter STARTED_AT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AgentSession session;
    private final AgentDefinition agent;

    private ai.mindconnect.chatui.ui.ChatHostLinks hostLinks =
            ai.mindconnect.chatui.ui.ChatHostLinks.NONE;

    /** Host-contributed links (the LLM-config href). Default: none. */
    public SessionInfoComponent withHostLinks(ai.mindconnect.chatui.ui.ChatHostLinks links) {
        this.hostLinks = links == null ? ai.mindconnect.chatui.ui.ChatHostLinks.NONE : links;
        return this;
    }

    /** A link when the host offers one, otherwise the bare config name. */
    private UiLink llmConfigLink() {
        String href = hostLinks.llmConfigHref(agent.llmConfigName());
        return UiLink.of("llmConfigName",
                href == null ? "" : href,
                agent.llmConfigName());
    }

    public SessionInfoComponent(AgentSession session, AgentDefinition agent) {
        this.session = session;
        this.agent = agent;
    }

    @Override
    public String id() {
        return "session-info-" + session.id();
    }

    @Override
    public UiDetail render() {
        return UiDetail.of(id(), "Session Info")
                .field(UiField.text("sessionId", "Session ID", session.id().toString()))
                .field(UiField.text("agent",     "Agent",      agent.name()))
                .link(llmConfigLink())
                .field(UiField.text("status",    "Status",
                        session.status() != null ? session.status().name() : ""))
                .field(UiField.text("userId",    "User",       session.userId()))
                .field(UiField.text("startedAt", "Started",
                        session.startedAt() != null
                                ? STARTED_AT_FMT.format(session.startedAt())
                                : ""))
                .field(UiField.text("title",     "Title",      session.title()))
                .link(backLink());
    }

    /** The host's way back, or an inert entry when it offers none. */
    private UiLink backLink() {
        String href = hostLinks.backHref(agent.id(), session.id());
        return href == null
                ? UiLink.of("back", "", "")
                : UiLink.of("back", href, "← Back to Agent");
    }
}
