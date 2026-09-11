package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAppShell;
import ai.mindconnect.ui.model.UiMenu;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.chatui.ui.controller.ChatUiController;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.ui.model.UiNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;

/**
 * The chat's own app shell, nested inside whatever shell the host provides:
 * the conversation history down the left, the agent and the session title
 * across the top, the conversation in the middle.
 *
 * <p>History entries are plain links to {@code /chat/sessions/{id}} rather
 * than dispatches. A conversation is a place you navigate to — the back
 * button, a bookmark and a middle-click all have to work, and a dispatch
 * that swaps the content without moving the URL breaks all three.
 */
public final class ChatShellComponent implements UiComponent {

    public static final String ID = "chat-app-shell";

    /** The header's burger targets the menu by id. */
    private static final String MENU_ID = "chat-menu";

    private final List<? extends AgentSessionHeader> sessions;
    private final AgentSession active;
    private final String agentName;
    private final UiNode content;
    private final java.util.Map<AgentId, String> agentIcons;
    private java.util.Set<SessionId> running = java.util.Set.of();
    private java.util.Set<SessionId> waiting = java.util.Set.of();

    public ChatShellComponent(List<? extends AgentSessionHeader> sessions, AgentSession active,
                              String agentName, UiNode content) {
        this(sessions, active, agentName, content, java.util.Map.of());
    }

    /**
     * @param agentIcons icon name per agent-definition id, so a history row
     *                   can wear the icon of the agent whose conversation it
     *                   is. A session whose agent is missing from the map —
     *                   an inline session agent, a deleted definition — falls
     *                   back to the generic one.
     */
    public ChatShellComponent(List<? extends AgentSessionHeader> sessions, AgentSession active,
                              String agentName, UiNode content,
                              java.util.Map<AgentId, String> agentIcons) {
        this.sessions = sessions;
        this.active = active;
        this.agentName = agentName;
        this.content = content;
        this.agentIcons = agentIcons == null ? java.util.Map.of() : agentIcons;
    }

    /**
     * Which conversations are busy right now, so their rows say so instead
     * of showing their age: {@code running} has a turn in flight,
     * {@code waiting} has a tool stopped at the approval gate. The server
     * renders the current state; the user stream keeps it current after
     * that (see chat-ui.js).
     */
    public ChatShellComponent withActivity(java.util.Set<SessionId> running, java.util.Set<SessionId> waiting) {
        this.running = running == null ? java.util.Set.of() : running;
        this.waiting = waiting == null ? java.util.Set.of() : waiting;
        return this;
    }

    /** The row badge of a chat that waits for an answer — the words chat-ui.js keys off. */
    public static final String BADGE_NEEDS_INPUT = "needs input";
    /** The row badge of a chat with a turn in flight. */
    public static final String BADGE_RUNNING = "running";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public UiAppShell render() {
        // Not fillViewport: that is min-height:100vh, which is right for a
        // top-level shell and wrong for one that starts below the host's own
        // header — it would push the composer exactly that far off screen.
        // The stylesheet stretches it to its parent instead.
        // No header. The chat used to render its own app-shell header — agent
        // name, session title, overflow — inside the host's, so the screen
        // carried two title bars where every other one carries a single list
        // header. The conversation's own header does that job now, and the
        // shell is left doing the one thing only it can: positioning the
        // history drawer over the content.
        return UiAppShell.of(ID)
                .menu(menu())
                .content(content);
    }

    /** New chat on top, then the conversations, newest first. */
    private UiMenu menu() {
        var menu = UiMenu.of(MENU_ID, "Chats");
        menu.side(UiMenu.Side.LEFT);
        // Collapsible and open by default: the history is the point of the
        // sidebar, but a wide conversation should be able to reclaim it.
        // A drawer, closed. The history is something you go and get, not
        // something that stands beside the conversation taking a third of the
        // width — and with it out of the flow the chat is laid out exactly
        // like every other page: nav, content, nothing else.
        menu.mode(UiMenu.Mode.OVERLAY);
        menu.state(UiMenu.State.HIDDEN);
        menu.toggle(true);
        menu.item(UiMenuItem.of("chat-new", "New chat").icon("add")
                .onClick(trigger(on(ChatUiController.class).createSession(null))));
        menu.item(UiMenuItem.divider());

        SessionId activeId = active == null ? null : active.id();
        for (AgentSessionHeader s : sessions) {
            String label = s.title() != null && !s.title().isBlank() ? s.title() : "New chat";
            String badge = waiting.contains(s.id()) ? BADGE_NEEDS_INPUT
                    : running.contains(s.id()) ? BADGE_RUNNING
                    : ago(s.startedAt());
            menu.item(UiMenuItem.link("chat-" + s.id().value(), label, "/chat/sessions/" + s.id().value())
                    .icon(iconFor(s))
                    .badge(badge)
                    .selected(s.id().equals(activeId)));
        }
        return menu;
    }

    /** The icon of the agent this conversation belongs to, or the generic one. */
    private String iconFor(AgentSessionHeader s) {
        AgentId agentId = s.agentDefinitionId();
        String icon = agentId == null ? null : agentIcons.get(agentId);
        return icon == null ? AgentDefinition.DEFAULT_ICON : icon;
    }

    private static String ago(Instant when) {
        if (when == null) return "";
        long minutes = Duration.between(when, Instant.now()).toMinutes();
        if (minutes < 60) return Math.max(minutes, 1) + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }
}
