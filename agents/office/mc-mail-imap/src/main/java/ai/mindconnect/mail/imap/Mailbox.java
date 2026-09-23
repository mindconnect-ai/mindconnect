package ai.mindconnect.mail.imap;

import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;
import jakarta.mail.Transport;
import ai.mindconnect.agent.tool.ConnectionTest;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * One open connection to one mailbox, for the length of one tool call.
 *
 * <p>Opened and closed per call, not pooled. A pool would have to be keyed by
 * user and kept honest across a password change, a token expiry and a server
 * that drops idle connections — for a handful of calls a minute that is a lot
 * of machinery to get wrong, and an IMAP connect is a round-trip, not a
 * session.
 *
 * <p>Everything here speaks in the {@code id} of {@link MailMessage}: the
 * IMAP UID, which survives other messages being deleted, or the message number
 * on POP3, which does not — see {@link #find}.
 */
public final class Mailbox implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Mailbox.class);

    private final MailAccount account;
    private final Store store;
    private final Folder folder;

    private Mailbox(MailAccount account, Store store, Folder folder) {
        this.account = account;
        this.store = store;
        this.folder = folder;
    }

    /**
     * Connects and opens {@code folderName} — the account's default folder
     * when it is null or blank.
     *
     * @param writable true to open for writing (setting a flag); false is read-only
     */
    public static Mailbox open(MailAccount account, String folderName, boolean writable) {
        Objects.requireNonNull(account, "account");
        String name = folderName == null || folderName.isBlank() ? account.folder() : folderName.strip();
        Session session = Session.getInstance(account.storeProperties());
        Store store = null;
        try {
            store = session.getStore(account.storeProtocol());
            store.connect(account.host(), account.port(), account.user(), account.password());
            Folder folder = account.isPop3() ? store.getFolder("INBOX") : store.getFolder(name);
            if (!folder.exists()) {
                throw new MailAccessException("There is no folder called \"" + name + "\" in your mailbox. "
                        + "Use email_list_folders to see what there is.");
            }
            folder.open(writable && !account.isPop3() ? Folder.READ_WRITE : Folder.READ_ONLY);
            return new Mailbox(account, store, folder);
        } catch (MessagingException e) {
            closeQuietly(store);
            throw new MailAccessException(explain(account, e), e);
        } catch (RuntimeException e) {
            closeQuietly(store);
            throw e;
        }
    }

    /**
     * Signs in, opens the default folder and — when the account can send —
     * signs in to SMTP as well, then says what it found. The Test button.
     *
     * <p>Both halves, because a mailbox that reads but cannot send fails on
     * the first message the agent tries to send, hours after anybody looked.
     */
    static ConnectionTest probe(MailAccount account) {
        StringBuilder found = new StringBuilder();
        try (Mailbox mailbox = open(account, null, false)) {
            found.append("Signed in to ").append(account.host()).append(" as ").append(account.user())
                    .append("; ").append(mailbox.folder.getFullName()).append(" holds ")
                    .append(mailbox.folder.getMessageCount()).append(" message")
                    .append(mailbox.folder.getMessageCount() == 1 ? "" : "s").append('.');
        } catch (MessagingException e) {
            return ConnectionTest.failed(explain(account, e));
        } catch (MailAccessException | MailConfigurationException e) {
            return ConnectionTest.failed(e.getMessage());
        }
        if (!account.canSend()) {
            return ConnectionTest.ok(found + " No SMTP server is set, so this mailbox is read-only.");
        }
        Session session = Session.getInstance(account.smtpProperties());
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(account.smtpHost(), account.smtpPort(), account.smtpUser(), account.smtpPassword());
            return ConnectionTest.ok(found + " " + account.smtpHost() + " accepted the sign-in for sending.");
        } catch (MessagingException e) {
            return ConnectionTest.failed(found + " But " + account.smtpHost() + " refused the sign-in for "
                    + "sending: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip())
                    + ". Check the SMTP fields.");
        }
    }

    /** Connects without opening a folder — for listing them. */
    public static List<String> folders(MailAccount account) {
        if (account.isPop3()) {
            return List.of("INBOX");                  // POP3 has exactly one, by definition
        }
        Session session = Session.getInstance(account.storeProperties());
        Store store = null;
        try {
            store = session.getStore(account.storeProtocol());
            store.connect(account.host(), account.port(), account.user(), account.password());
            List<String> names = new ArrayList<>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                // A folder that cannot hold messages is a node in the tree, not a place to read.
                if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
                    names.add(folder.getFullName());
                }
            }
            return List.copyOf(names);
        } catch (MessagingException e) {
            throw new MailAccessException(explain(account, e), e);
        } finally {
            closeQuietly(store);
        }
    }

    /** Names a wastebasket goes by when the server does not mark one — web.de, GMX, Outlook, Dovecot. */
    private static final List<String> TRASH_NAMES = List.of("trash", "papierkorb", "deleted items",
            "deleted messages", "gelöschte elemente", "gelöschte objekte", "bin", "corbeille", "papelera");

    /** Names a sent folder goes by when the server does not mark one — Apple Mail's among them. */
    private static final List<String> SENT_NAMES = List.of("sent", "sent messages", "sent items",
            "sent mail", "gesendet", "gesendete objekte", "gesendete elemente", "envoyés", "enviados");

    /**
     * The mailbox's wastebasket: the folder the server marks {@code \\Trash}
     * (RFC 6154), or else the one whose name says so. Null when there is
     * none — POP3, or a server with neither.
     */
    public static String trash(MailAccount account) {
        return specialFolder(account, "\\Trash", TRASH_NAMES);
    }

    /** The mailbox's sent folder, found the way {@link #trash} finds the wastebasket. */
    public static String sent(MailAccount account) {
        return specialFolder(account, "\\Sent", SENT_NAMES);
    }

    /**
     * A folder by its RFC 6154 special-use attribute, or else by the first of
     * {@code names} (in their order) that one of the folders is called.
     */
    private static String specialFolder(MailAccount account, String attribute, List<String> names) {
        if (account.isPop3()) return null;
        Session session = Session.getInstance(account.storeProperties());
        Store store = null;
        try {
            store = session.getStore(account.storeProtocol());
            store.connect(account.host(), account.port(), account.user(), account.password());
            java.util.Map<String, String> byName = new java.util.HashMap<>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                if (folder instanceof org.eclipse.angus.mail.imap.IMAPFolder imap) {
                    for (String flag : imap.getAttributes()) {
                        if (attribute.equalsIgnoreCase(flag)) return folder.getFullName();
                    }
                }
                byName.putIfAbsent(folder.getName().toLowerCase(java.util.Locale.ROOT), folder.getFullName());
            }
            for (String name : names) {
                if (byName.containsKey(name)) return byName.get(name);
            }
            return null;
        } catch (MessagingException e) {
            throw new MailAccessException(explain(account, e), e);
        } finally {
            closeQuietly(store);
        }
    }

    /**
     * Puts a copy of a message that went out over SMTP into the sent folder,
     * marked as read. SMTP only delivers; keeping a copy is the client's job,
     * and a client that skips it leaves the sent folder empty of everything
     * it sent.
     *
     * @return the folder it went into, or null when the mailbox has none
     */
    public static String saveSent(MailAccount account, Message message) {
        String sent = sent(account);
        if (sent == null) return null;
        Session session = Session.getInstance(account.storeProperties());
        Store store = null;
        try {
            store = session.getStore(account.storeProtocol());
            store.connect(account.host(), account.port(), account.user(), account.password());
            Folder folder = store.getFolder(sent);
            message.setFlag(Flags.Flag.SEEN, true);
            folder.appendMessages(new Message[] {message});
            return sent;
        } catch (MessagingException e) {
            throw new MailAccessException("Could not keep a copy in " + sent + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(store);
        }
    }

    /** The message's {@code Message-ID} header — the name it keeps in every folder. Null when it has none. */
    public String messageIdHeader(String id) {
        try {
            String[] values = find(id).getHeader("Message-ID");
            return values == null || values.length == 0 ? null : values[0].strip();
        } catch (MessagingException e) {
            throw new MailAccessException(explain(account, e), e);
        }
    }

    /**
     * Moves the messages in this folder with that {@code Message-ID} to
     * {@code targetName} — how Undo finds a deleted message, whose UID the
     * move to the wastebasket changed.
     *
     * @return how many were moved
     */
    public int moveByMessageIdHeader(String messageIdHeader, String targetName) {
        if (messageIdHeader == null || messageIdHeader.isBlank()) return 0;
        try {
            Message[] found = folder.search(new jakarta.mail.search.MessageIDTerm(messageIdHeader.strip()));
            if (found.length == 0) return 0;
            Folder target = store.getFolder(targetName.strip());
            if (folder instanceof org.eclipse.angus.mail.imap.IMAPFolder imap) {
                imap.moveMessages(found, target);
            } else {
                folder.copyMessages(found, target);
                for (Message message : found) message.setFlag(Flags.Flag.DELETED, true);
                folder.expunge();
            }
            return found.length;
        } catch (MessagingException e) {
            throw new MailAccessException("Could not bring that message back: " + e.getMessage(), e);
        }
    }

    /** This folder's name as the server spells it. */
    public String folderName() {
        return folder.getFullName();
    }

    /**
     * The newest {@code limit} messages that match, newest first, as summaries
     * — headers only, no bodies: a listing of fifty mails must not pull fifty
     * bodies over the wire, and the model asks for the ones it wants.
     */
    public Page list(int limit, int offset, boolean unseenOnly, String from, String subject, Instant since, Instant before) {
        try {
            Message[] found = search(unseenOnly, from, subject, since, before);
            // Arrival order is the server's order; the newest are at the end.
            // A page further back skips the newest first.
            List<Message> wanted = new ArrayList<>();
            for (int i = found.length - 1 - offset; i >= 0 && wanted.size() < limit; i--) {
                wanted.add(found[i]);
            }
            prefetch(wanted);
            List<MailMessage> out = new ArrayList<>(wanted.size());
            for (Message message : wanted) {
                out.add(summary(message));
            }
            return new Page(out, found.length, offset);
        } catch (MessagingException e) {
            throw new MailAccessException(explain(account, e), e);
        }
    }

    /**
     * One page of a listing: the messages, how many match in all, and where
     * the page started — enough to say "41–60 of 1234" and where the next one
     * begins.
     */
    public record Page(List<MailMessage> messages, int matching, int offset) { }

    /** How many messages the folder holds, for the line above a listing. */
    public int size() {
        try {
            return folder.getMessageCount();
        } catch (MessagingException e) {
            return -1;
        }
    }

    /**
     * The named messages as a listing shows them — envelopes and flags, no
     * bodies — in one FETCH.
     *
     * <p>UIDs are how IMAP addresses a set: {@code UID FETCH 12,17,23
     * (ENVELOPE FLAGS)} is one command for the lot, where reading them means
     * pulling every body over the wire. Ids that name nothing any more are
     * left out, the same as {@link #read} on a message that has gone.
     */
    public List<MailMessage> summaries(List<String> ids) {
        try {
            List<Message> found = new ArrayList<>(ids.size());
            if (!account.isPop3() && folder instanceof UIDFolder uids) {
                long[] wanted = new long[ids.size()];
                int at = 0;
                for (String id : ids) {
                    try {
                        wanted[at++] = Long.parseLong(id.strip());
                    } catch (NumberFormatException e) {
                        at--; // Not an id at all; the rest still answer.
                    }
                }
                for (Message message : uids.getMessagesByUID(Arrays.copyOf(wanted, at))) {
                    if (message != null && !message.isExpunged()) found.add(message);
                }
            } else {
                for (String id : ids) {
                    try {
                        found.add(find(id));
                    } catch (MailAccessException e) {
                        // Gone since it was listed.
                    }
                }
            }
            prefetch(found);
            List<MailMessage> out = new ArrayList<>(found.size());
            for (Message message : found) {
                out.add(summary(message));
            }
            return out;
        } catch (MessagingException e) {
            throw new MailAccessException(explain(account, e), e);
        }
    }

    /** One message with its text. */
    public MailMessage read(String id, int maxChars) {
        Message message = find(id);
        try {
            MailText.Extract extract = MailText.of(message);
            String text = extract.text();
            String cut = MailText.truncate(text, maxChars);
            return new MailMessage(id, here(), message.getSubject(), addresses(message.getFrom()),
                    addressList(message.getRecipients(Message.RecipientType.TO)),
                    received(message), seen(message), !extract.attachments().isEmpty(),
                    extract.attachments(), cut, cut != null && !cut.equals(text));
        } catch (MessagingException | IOException e) {
            throw new MailAccessException("Could not read that message: " + e.getMessage(), e);
        }
    }

    /**
     * One message's body as it was sent: the HTML part when there is one and
     * the plain part beside it, neither reduced to words.
     *
     * <p>{@link #read} exists for a model, and strips the HTML down to its
     * text. A screen shows the mail itself, so it needs the parts untouched —
     * and takes on the job of sanitising them before a browser sees them.
     */
    public MailText.Extract body(String id) {
        Message message = find(id);
        try {
            return MailText.of(message);
        } catch (MessagingException | IOException e) {
            throw new MailAccessException("Could not read that message: " + e.getMessage(), e);
        }
    }

    /** The attachments of one message: names, types and sizes, no content. */
    public List<MailText.Attached> attachments(String id) {
        Message message = find(id);
        try {
            return MailText.describe(MailText.attachmentParts(message));
        } catch (MessagingException | IOException e) {
            throw new MailAccessException("Could not read that message: " + e.getMessage(), e);
        }
    }

    /**
     * One attachment's content, by its place among the message's attachments
     * — the {@code index} {@link #attachments} gave it.
     */
    public MailText.AttachedFile attachment(String id, int index) {
        Message message = find(id);
        try {
            List<jakarta.mail.Part> parts = MailText.attachmentParts(message);
            if (index < 0 || index >= parts.size()) {
                throw new MailAccessException("That message has no attachment number " + (index + 1) + ".");
            }
            return MailText.file(parts.get(index));
        } catch (MessagingException | IOException e) {
            throw new MailAccessException("Could not read that attachment: " + e.getMessage(), e);
        }
    }

    /** Sets or clears the read flag; IMAP only, because POP3 has no flags to set. */
    public void setSeen(String id, boolean seen) {
        if (account.isPop3()) {
            throw new MailAccessException("POP3 keeps no read/unread flag, so there is nothing to set. "
                    + "Change that mailbox to imap under Connections if your provider offers it.");
        }
        Message message = find(id);
        try {
            message.setFlag(Flags.Flag.SEEN, seen);
        } catch (MessagingException e) {
            throw new MailAccessException("Could not change that message: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes one message for good: flagged and expunged, in one go — a
     * DELETED flag alone would come back on the next reconnect, and a folder
     * closed with expunge would take every flagged message with it.
     *
     * @return the subject, so the answer can say what went
     */
    public String delete(String id) {
        Message message = find(id);
        try {
            String subject = message.getSubject();
            if (account.isPop3()) {
                // POP3 has no expunge; deletion happens when the folder closes
                // with expunge=true, and only this one message is flagged.
                message.setFlag(Flags.Flag.DELETED, true);
                folder.close(true);
            } else {
                message.setFlag(Flags.Flag.DELETED, true);
                folder.expunge();
            }
            return subject == null ? "(no subject)" : subject;
        } catch (MessagingException e) {
            throw new MailAccessException("Could not delete that message: " + e.getMessage(), e);
        }
    }

    /** What a move answers: the subject, for a sentence, and the UID the message has now — when the server said. */
    public record Moved(String subject, String newUid) { }

    /**
     * Moves one message to another folder of the same mailbox. IMAP MOVE where
     * the server has it, copy-then-expunge where it does not; either way the
     * message is in one place afterwards.
     *
     * <p>The message has a new UID in the target folder. A server with UIDPLUS
     * (RFC 4315) says which in its {@code COPYUID} answer, and that is what a
     * list that keeps the message needs; one without leaves {@code newUid}
     * null, and the message has to be found again by its {@code Message-ID}.
     */
    public Moved move(String id, String targetName) {
        if (account.isPop3()) {
            throw new MailAccessException("POP3 has exactly one folder, so there is nowhere to move a "
                    + "message to. Change that mailbox to imap under Connections if your provider offers it.");
        }
        if (targetName == null || targetName.isBlank()) {
            throw new MailAccessException("\"to\" is required — the folder to move the message to.");
        }
        Message message = find(id);
        try {
            Folder target = store.getFolder(targetName.strip());
            if (!target.exists()) {
                throw new MailAccessException("There is no folder called \"" + targetName.strip()
                        + "\" in your mailbox. Use email_list_folders to see what there is.");
            }
            if (target.getFullName().equals(folder.getFullName())) {
                throw new MailAccessException("The message is already in " + folderName() + ".");
            }
            String subject = message.getSubject();
            Message[] one = {message};
            String newUid = null;
            if (folder instanceof org.eclipse.angus.mail.imap.IMAPFolder imap) {
                // MOVE when the server has it, COPY+DELETE+EXPUNGE when not; the
                // UID variant hands back COPYUID where the server supports UIDPLUS.
                org.eclipse.angus.mail.imap.AppendUID[] answered = imap.moveUIDMessages(one, target);
                if (answered != null && answered.length > 0 && answered[0] != null) {
                    newUid = String.valueOf(answered[0].uid);
                }
            } else {
                folder.copyMessages(one, target);
                message.setFlag(Flags.Flag.DELETED, true);
                folder.expunge();
            }
            return new Moved(subject == null ? "(no subject)" : subject, newUid);
        } catch (MessagingException e) {
            throw new MailAccessException("Could not move that message: " + e.getMessage(), e);
        }
    }

    /**
     * The message behind an id.
     *
     * <p>On IMAP that is a UID and the folder looks it up. On POP3 there are
     * no UIDs to look up, so the id is the message number — which shifts when
     * something before it is deleted. Said plainly rather than papered over:
     * an agent that reads a POP3 inbox and then acts on "message 4" a minute
     * later can act on the wrong one.
     */
    private Message find(String id) {
        try {
            if (account.isPop3()) {
                int number = number(id);
                if (number < 1 || number > folder.getMessageCount()) {
                    throw new NoSuchMessageException("There is no message " + id + " in " + folderName() + ".");
                }
                return folder.getMessage(number);
            }
            if (!(folder instanceof UIDFolder uids)) {
                return folder.getMessage(number(id));
            }
            Message message = uids.getMessageByUID(Long.parseLong(id.strip()));
            if (message == null) {
                throw new NoSuchMessageException("There is no message " + id + " in " + folderName()
                        + " any more — it may have been moved or deleted.");
            }
            return message;
        } catch (NumberFormatException e) {
            throw new MailAccessException("\"" + id + "\" is not a message id. "
                    + "Use the id email_list_messages gave for that message.");
        } catch (MessagingException e) {
            throw new MailAccessException(explain(account, e), e);
        }
    }

    private static int number(String id) {
        return Integer.parseInt(id.strip());
    }

    private Message[] search(boolean unseenOnly, String from, String subject, Instant since, Instant before)
            throws MessagingException {
        List<SearchTerm> terms = new ArrayList<>();
        if (unseenOnly && !account.isPop3()) {
            terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        }
        if (from != null && !from.isBlank()) terms.add(new FromStringTerm(from.strip()));
        if (subject != null && !subject.isBlank()) terms.add(new SubjectTerm(subject.strip()));
        if (since != null) terms.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
        if (before != null) terms.add(new ReceivedDateTerm(ComparisonTerm.LT, Date.from(before)));
        if (terms.isEmpty()) {
            return folder.getMessages();
        }
        if (account.isPop3()) {
            // POP3 has no SEARCH: the server would refuse, so the filtering
            // happens here, over the headers the folder can fetch.
            return filterLocally(folder.getMessages(), from, subject, since, before);
        }
        SearchTerm term = terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(new SearchTerm[0]));
        return folder.search(term);
    }

    private static Message[] filterLocally(Message[] messages, String from, String subject, Instant since,
                                           Instant before) {
        return Arrays.stream(messages).filter(message -> {
            try {
                if (from != null && !from.isBlank()
                        && !addresses(message.getFrom()).toLowerCase(java.util.Locale.ROOT)
                                .contains(from.strip().toLowerCase(java.util.Locale.ROOT))) {
                    return false;
                }
                if (subject != null && !subject.isBlank()) {
                    String value = message.getSubject();
                    if (value == null || !value.toLowerCase(java.util.Locale.ROOT)
                            .contains(subject.strip().toLowerCase(java.util.Locale.ROOT))) {
                        return false;
                    }
                }
                if (since != null || before != null) {
                    Date date = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
                    if (date == null) return false;
                    if (since != null && date.toInstant().isBefore(since)) return false;
                    return before == null || date.toInstant().isBefore(before);
                }
                return true;
            } catch (MessagingException e) {
                log.debug("Skipping a message that could not be read for filtering: {}", e.getMessage());
                return false;
            }
        }).toArray(Message[]::new);
    }

    /**
     * Asks for the whole page's envelopes, flags and uids in one command.
     *
     * <p>Without this, every {@code getSubject()} and every {@code getUID()}
     * is its own FETCH: a page of twenty-five messages is a hundred round
     * trips to the server, and a listing that should take a moment takes
     * four seconds against a mailbox across the internet. IMAP has had one
     * command for this since 1996, and jakarta.mail calls it a FetchProfile.
     *
     * <p>Best effort: a server that refuses the fetch still answers the
     * individual requests, slowly. A slow listing beats none.
     */
    private void prefetch(List<Message> messages) {
        if (messages.isEmpty() || account.isPop3()) return;
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(FetchProfile.Item.FLAGS);
        if (folder instanceof UIDFolder) {
            profile.add(UIDFolder.FetchProfileItem.UID);
        }
        try {
            folder.fetch(messages.toArray(new Message[0]), profile);
        } catch (MessagingException e) {
            log.debug("Could not prefetch a page of {}: {}", messages.size(), e.getMessage());
        }
    }

    /** Where everything read here lies: this account, this folder. */
    private Location here() {
        return new Location(account.id(), folderName());
    }

    private MailMessage summary(Message message) throws MessagingException {
        return new MailMessage(idOf(message), here(), message.getSubject(), addresses(message.getFrom()),
                addressList(message.getRecipients(Message.RecipientType.TO)), received(message), seen(message),
                false, List.of(), null, false);
    }

    private String idOf(Message message) throws MessagingException {
        if (!account.isPop3() && folder instanceof UIDFolder uids) {
            return String.valueOf(uids.getUID(message));
        }
        return String.valueOf(message.getMessageNumber());
    }

    private boolean seen(Message message) {
        try {
            return !account.isPop3() && message.isSet(Flags.Flag.SEEN);
        } catch (MessagingException e) {
            return false;
        }
    }

    private static Instant received(Message message) throws MessagingException {
        Date date = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        return date == null ? null : date.toInstant();
    }

    static String addresses(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) return "(unknown)";
        return String.join(", ", addressList(addresses));
    }

    static List<String> addressList(jakarta.mail.Address[] addresses) {
        if (addresses == null) return List.of();
        return Arrays.stream(addresses).map(address -> address instanceof InternetAddress internet
                        ? (internet.getPersonal() != null
                            ? internet.getPersonal() + " <" + internet.getAddress() + ">"
                            : internet.getAddress())
                        : address.toString())
                .toList();
    }

    /**
     * What went wrong, as the person who has to fix it would put it. A stack
     * trace in the chat helps nobody; "your password was refused" does.
     */
    static String explain(MailAccount account, MessagingException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (e instanceof jakarta.mail.AuthenticationFailedException || lower.contains("authenticationfailed")
                || lower.contains("invalid credentials") || lower.contains("login failed")) {
            return "The mail server refused " + account.user() + ". Open that mailbox under Connections in "
                    + "your profile and check the account and password — where your provider requires an "
                    + "app-specific password, your normal one is refused here.";
        }
        if (lower.contains("connect") || lower.contains("timed out") || lower.contains("unknown host")) {
            return "Could not reach " + account.host() + ":" + account.port() + ". Open that mailbox under "
                    + "Connections in your profile and check the server, its port and its encryption.";
        }
        return "The mail server said: " + (message.isBlank() ? e.toString() : message);
    }

    private static void closeQuietly(Store store) {
        if (store == null) return;
        try {
            store.close();
        } catch (MessagingException ignored) {
            // The connection is going away either way.
        }
    }

    @Override
    public void close() {
        try {
            if (folder.isOpen()) folder.close(false);   // false: never expunge, we delete nothing
        } catch (MessagingException e) {
            log.debug("Could not close folder {}: {}", folder.getFullName(), e.getMessage());
        }
        closeQuietly(store);
    }
}
