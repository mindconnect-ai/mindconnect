package ai.mindconnect.agent.runtime.store;

import java.util.Locale;

import ai.mindconnect.plugin.Setting;

/**
 * Where a session's messages are kept: as a conversation of the message
 * repository, the way sessions were kept so far, or in a repository of
 * their own. Both stay until the stored sessions have moved over.
 */
public enum SessionMessages {

    /** In the message repository, through {@link RepositorySessionStore}. */
    CONVERSATION,

    /** In a {@link SessionMessageRepository}, through {@link DefaultSessionStore}. */
    OWN;

    /** The setting that chooses; every plugin that provides a session store reads it. */
    public static final Setting<SessionMessages> SETTING = Setting.of("sessions.messages",
            "Where a session's messages are kept: conversation (the message repository) or own",
            SessionMessages::parse).defaultsTo(CONVERSATION);

    private static SessionMessages parse(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "conversation" -> CONVERSATION;
            case "own" -> OWN;
            default -> throw new IllegalArgumentException("neither conversation nor own");
        };
    }
}
