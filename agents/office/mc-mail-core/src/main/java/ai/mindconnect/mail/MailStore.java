package ai.mindconnect.mail;

import ai.mindconnect.mail.MailMessage;

import java.util.List;

/**
 * One mailbox, as the client uses it — whichever of the three providers is
 * behind it.
 *
 * <p>The port exists so that the screens know about mail and not about IMAP,
 * Graph or Gmail. Everything the client can do to a mailbox is on this
 * interface, and the list is deliberately short: this is a client, and the
 * two things it must never do are delete and send without being told to.
 *
 * <p>{@link MailMessage} is the tool modules' record, reused rather than
 * copied. It is already provider-neutral — id, folder, subject, from, to,
 * received, seen, attachments, body — because three tool modules had to agree
 * on it first. §5.2 of the concept moves it, with this port, into a shared
 * {@code mc-mail-core}.
 *
 * <p>A store is opened for one account and closed after the request that
 * opened it. Implementations hold a connection (IMAP) or nothing at all
 * (Graph, Gmail); neither is safe to keep across requests, and an IMAP
 * connect is a round-trip, not a session.
 */
public interface MailStore extends AutoCloseable {

    /** The folders, in the order the left column shows them; the inbox first. */
    List<MailFolder> folders();

    /**
     * One page of a folder, headers only.
     *
     * @param folderId   the folder, as {@link #folders()} named it
     * @param skip       how many of the newest to pass over — page two of
     *                   twenty-five passes twenty-five
     * @param limit      at most this many
     * @param unreadOnly leave the read ones out
     * @param search     free text the provider matches against sender and
     *                   subject, or null for everything
     */
    default MailPage list(String folderId, int skip, int limit, boolean unreadOnly, String search) {
        return list(folderId, skip, limit, MailQuery.of(unreadOnly, search));
    }

    /**
     * One page of a folder, headers only, narrowed by {@code query} — sender,
     * subject and dates beside unread and the free-text search.
     */
    MailPage list(String folderId, int skip, int limit, MailQuery query);

    /**
     * Several messages of one folder, in the order asked for; one that is no
     * longer there is left out rather than failing the rest.
     *
     * <p>The default reads them one after the other, which is right for a
     * provider that answers each over its own request. A store whose
     * connection is expensive to open — IMAP — opens the folder once instead,
     * which is the difference between a second and a minute for fifty.
     */
    default List<MailMessage> read(String folderId, List<String> messageIds) {
        List<MailMessage> found = new java.util.ArrayList<>();
        for (String id : messageIds) {
            try {
                found.add(read(folderId, id));
            } catch (MailStoreException e) {
                // Gone since it was listed; the others still answer.
            }
        }
        return found;
    }

    /** One message in full: headers, text, and the names of its attachments. */
    MailMessage read(String folderId, String messageId);

    /**
     * The same message's body in both shapes — the sender's HTML where there
     * was any, and the words.
     *
     * <p>Separate from {@link #read} because it costs a second fetch on two
     * of the three providers and only a screen that is about to display the
     * message needs it.
     */
    MailBody body(String folderId, String messageId);

    /**
     * The files attached to a message, without their content — for the
     * reading pane to list. Empty for a store that cannot tell.
     */
    default List<MailAttachment> attachments(String folderId, String messageId) {
        return List.of();
    }

    /**
     * One attachment's content.
     *
     * @param attachmentId the {@link MailAttachment#id()} {@link #attachments} gave it
     */
    default MailFile attachment(String folderId, String messageId, String attachmentId) {
        throw new MailStoreException("This mailbox cannot open attachments.");
    }

    /** Flag a message read, or set it back to unread. */
    void setSeen(String folderId, String messageId, boolean seen);

    /**
     * Whether this account can send at all. An IMAP mailbox without an SMTP
     * server is a real and common case, and the composer says so rather than
     * failing at the last step.
     */
    /**
     * True when messages can be moved and deleted here.
     *
     * <p>False for POP3, which has one folder and no notion of moving
     * between folders. The screen hides the buttons rather than offering
     * two that answer with an apology.
     */
    boolean canOrganise();

    /**
     * Moves messages to the mailbox's wastebasket.
     *
     * @return the receipt {@link #restore} needs to bring them back; empty
     *         when there is nothing to undo
     *
     * <p>Deliberately not an expunge. All three providers have somewhere
     * things go before they are gone — IMAP's Trash, Graph's Deleted Items,
     * Gmail's TRASH label — and a screen with a Delete button that could not
     * be undone from the provider's own client would be the wrong kind of
     * honest.
     */
    java.util.List<String> delete(String folderId, java.util.List<String> messageIds);

    /**
     * Brings deleted messages back into {@code folderId} — the Undo of
     * {@link #delete}.
     *
     * @param receipt what {@link #delete} returned: the handles the messages
     *                have in the wastebasket, which are not always the ids
     *                they had before (Graph gives a moved message a new id,
     *                IMAP a new UID)
     */
    void restore(String folderId, java.util.List<String> receipt);

    /** Moves messages to another folder of the same mailbox. */
    void move(String folderId, java.util.List<String> messageIds, String targetFolderId);

    boolean canSend();

    /**
     * Sends. The one call here that leaves the installation and cannot be
     * recalled — everything that guards it sits above this port, in the
     * composer and the pre-send review.
     *
     * @return a sentence for the toast: who it went to
     */
    String send(MailDraft draft);

    @Override
    void close();
}
