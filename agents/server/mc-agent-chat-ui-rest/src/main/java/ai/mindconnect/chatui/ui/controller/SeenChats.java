package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one browser session has had on screen of the chat, and since when:
 * enough to tell a conversation started elsewhere — in another tab, over the
 * REST API — from one the user has already looked at. Kept in the HTTP
 * session; immutable, so the new value is stored back after every change.
 *
 * @param since when this browser session first showed a chat; chats started
 *              before it are not new to it
 * @param shown ids of the chats it has had on screen
 */
record SeenChats(Instant since, Set<String> shown) implements Serializable {

    SeenChats {
        shown = shown == null ? Set.of() : Set.copyOf(shown);
    }

    /** A browser session that begins now and has shown nothing yet. */
    static SeenChats from(Instant since) {
        return new SeenChats(since, Set.of());
    }

    /** The same record with {@code chat} on it. */
    SeenChats withShown(SessionId chat) {
        if (shown.contains(chat.value())) return this;
        Set<String> more = new HashSet<>(shown);
        more.add(chat.value());
        return new SeenChats(since, more);
    }

    /** The chats started after this browser session began that it has not had on screen. */
    Set<SessionId> unseen(List<? extends AgentSessionHeader> chats) {
        return chats.stream()
                .filter(chat -> chat.startedAt() != null && chat.startedAt().isAfter(since))
                .filter(chat -> !shown.contains(chat.id().value()))
                .map(AgentSessionHeader::id)
                .collect(Collectors.toUnmodifiableSet());
    }
}
