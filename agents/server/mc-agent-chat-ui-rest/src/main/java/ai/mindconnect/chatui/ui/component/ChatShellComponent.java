package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.chatui.ui.ChatHostLinks;
import ai.mindconnect.ui.model.UiAppShell;
import ai.mindconnect.ui.model.UiMenuButton;
import ai.mindconnect.ui.model.UiHeader;
import ai.mindconnect.ui.model.UiMenu;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiText;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    private final List<AgentSession> sessions;
    private final AgentSession active;
    private final String agentName;
    private final UiNode content;
    private ChatHostLinks hostLinks = ChatHostLinks.NONE;

    public ChatShellComponent(List<AgentSession> sessions, AgentSession active,
                              String agentName, UiNode content) {
        this.sessions = sessions;
        this.active = active;
        this.agentName = agentName;
        this.content = content;
    }

    /**
     * What the embedding app adds to the chat. The links land in an overflow
     * menu rather than the header itself: they are a way out of the
     * conversation, and a conversation should not be framed by five exits.
     */
    public ChatShellComponent withHostLinks(ChatHostLinks links) {
        this.hostLinks = links == null ? ChatHostLinks.NONE : links;
        return this;
    }

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
        return UiAppShell.of(ID)
                .header(header())
                .menu(menu())
                .content(content);
    }

    /**
     * The agent leads, the session title follows it, and the burger folds the
     * history away — a long conversation deserves the width. Everything that
     * leaves the chat (back to the agent, the host's inspection dialogs) hides
     * behind one overflow button on the right.
     */
    private UiHeader header() {
        var header = UiHeader.of(agentName == null ? "Chat" : agentName)
                .brandHref("/chat")
                .menuToggle(MENU_ID);
        if (active == null) {
            return header;
        }
        String title = active.title() != null && !active.title().isBlank()
                ? active.title()
                : "New chat";
        header.extra(UiText.of("chat-title", title));

        var overflow = overflowMenu();
        if (overflow != null) {
            header.extra(overflow);
        }
        return header;
    }

    /**
     * The exits, collected: up to the parent session for a sub-agent chat,
     * back to the agent, and whatever dialogs the host offers. Null when there
     * is nothing to show — a standalone chat publishes no host links and has
     * no parent, so it gets no button at all.
     */
    private UiMenuButton overflowMenu() {
        var items = new java.util.ArrayList<UiMenuItem>();

        if (active.parentSessionId() != null) {
            items.add(UiMenuItem.link("parent", "Parent session",
                    "/chat/sessions/" + active.parentSessionId()).icon("arrow-up"));
        }
        // Only a chat that references a registry agent has an agent to go back
        // to. An inline session agent's id resolves to nothing, so the link
        // would land on an agent page for an agent that does not exist.
        if (boundToRegistryAgent()) {
            String back = hostLinks.backHref(active.agentDefinitionId(), active.id());
            if (back != null) {
                items.add(UiMenuItem.link("back", "Back to agent", back).icon("back"));
            }
        }
        for (var tool : hostLinks.sessionTools(active.id())) {
            items.add(UiMenuItem.of(tool.id(), tool.label()).icon(tool.icon())
                    .onClick(ai.mindconnect.ui.model.UiTrigger.api("GET", tool.url())));
        }
        if (items.isEmpty()) {
            return null;
        }
        var button = UiMenuButton.of("chat-overflow");
        button.icon("more");
        items.forEach(button::item);
        return button;
    }

    /** New chat on top, then the conversations, newest first. */
    private UiMenu menu() {
        var menu = UiMenu.of(MENU_ID, "Chats");
        menu.side(UiMenu.Side.LEFT);
        // Collapsible and open by default: the history is the point of the
        // sidebar, but a wide conversation should be able to reclaim it.
        menu.mode(UiMenu.Mode.PUSH);
        menu.state(UiMenu.State.EXPANDED);
        menu.toggle(true);
        menu.item(UiMenuItem.of("chat-new", "New chat").icon("add")
                .onClick(ai.mindconnect.ui.model.UiTrigger.api("POST", "/chat/api/sessions")));
        menu.item(UiMenuItem.divider());

        UUID activeId = active == null ? null : active.id();
        for (AgentSession s : sessions) {
            String label = s.title() != null && !s.title().isBlank() ? s.title() : "New chat";
            menu.item(UiMenuItem.link("chat-" + s.id(), label, "/chat/sessions/" + s.id())
                    .icon("chat")
                    .badge(ago(s.startedAt()))
                    .selected(s.id().equals(activeId)));
        }
        return menu;
    }

    /**
     * Whether this chat runs an agent from the registry — either a
     * {@code ref} session agent, or a session from before session agents
     * existed, whose {@code agentDefinitionId} was always a real one.
     */
    private boolean boundToRegistryAgent() {
        return active.mainAgent()
                .map(a -> a instanceof ai.mindconnect.agent.domain.session.SessionAgentRef)
                .orElse(true);
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
