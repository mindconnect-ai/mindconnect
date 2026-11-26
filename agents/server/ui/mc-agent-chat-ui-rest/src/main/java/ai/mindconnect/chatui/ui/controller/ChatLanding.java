package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedSession;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which chat {@code /chat} opens. The one this browser last had on screen,
 * as long as the user still has it; otherwise the most recently started one
 * that has been used — somebody wrote in it.
 *
 * <p>Opening the most recently started chat every time made the chat jump:
 * a user who came back through the menu, or whose page re-requested
 * {@code /chat}, landed in whatever chat had been started last — in another
 * tab, over the REST API — instead of the one they were working in.
 *
 * <p>An empty chat is passed over when nothing is on record, which is every
 * fresh sign-in: a chat somebody opened and never wrote in — a click on the
 * wrong agent, a page that started one — is not what they came back for.
 */
final class ChatLanding {

    /** How many empty chats in a row are looked past before giving up; each look may read the store. */
    static final int MAX_LOOKS = 20;

    private ChatLanding() {}

    /**
     * @param newestFirst the user's chats, most recently started first
     * @param lastShown   the chat last on screen in this browser session, or {@code null}
     * @param used        whether a chat has been written in
     * @return the chat to open; empty when the user has none worth opening
     */
    static Optional<SessionId> pick(List<ManagedSession> newestFirst, SessionId lastShown,
                                    Predicate<ManagedSession> used) {
        if (lastShown != null
                && newestFirst.stream().anyMatch(chat -> lastShown.value().equals(chat.id().value()))) {
            return Optional.of(lastShown);
        }
        return newestFirst.stream().limit(MAX_LOOKS)
                .filter(chat -> titled(chat) || used.test(chat))
                .map(chat -> chat.id())
                .findFirst();
    }

    /** A title comes with the first answer, or from the user: a titled chat was used, no need to look. */
    private static boolean titled(ManagedSession chat) {
        return chat.title() != null && !chat.title().isBlank();
    }
}
