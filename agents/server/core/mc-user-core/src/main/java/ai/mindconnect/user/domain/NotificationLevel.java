package ai.mindconnect.user.domain;

/**
 * How much of the user's attention a notification is asking for. It decides
 * how the bell counts it and how the entry looks — nothing else; a level is
 * not a permission and not a category.
 */
public enum NotificationLevel {

    /** Worth knowing, nothing to do — an import finished, a namespace was shared. */
    INFO,

    /** Something is not as it should be, and the user can put it right. */
    WARNING,

    /**
     * The user has to do something before a part of the installation works
     * for them — configure their mailbox, bring their own API key. This is
     * what a sign-in notice is about, and the only level the bell insists on.
     */
    ACTION_REQUIRED
}
