package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;

import java.util.List;
import java.util.Optional;

/**
 * Which chat {@code /chat} opens. The one this browser last had on screen,
 * as long as the user still has it; otherwise the most recently started.
 *
 * <p>Opening the most recently started chat every time made the chat jump:
 * a user who came back through the menu, or whose page re-requested
 * {@code /chat}, landed in whatever chat had been started last — in another
 * tab, over the REST API — instead of the one they were working in.
 */
final class ChatLanding {

    private ChatLanding() {}

    /**
     * @param newestFirst the user's chats, most recently started first
     * @param lastShown   the chat last on screen in this browser session, or {@code null}
     * @return the chat to open; empty when the user has none
     */
    static Optional<SessionId> pick(List<? extends AgentSessionHeader> newestFirst, SessionId lastShown) {
        if (lastShown != null && newestFirst.stream().anyMatch(chat -> lastShown.equals(chat.id()))) {
            return Optional.of(lastShown);
        }
        return newestFirst.isEmpty() ? Optional.empty() : Optional.of(newestFirst.get(0).id());
    }
}
