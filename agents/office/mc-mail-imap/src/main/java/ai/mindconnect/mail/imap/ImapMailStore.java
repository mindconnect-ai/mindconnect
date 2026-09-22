package ai.mindconnect.mail.imap;

import ai.mindconnect.mail.MailStoreException;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.MailQuery;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailFolder;
import ai.mindconnect.mail.MailFile;
import ai.mindconnect.mail.MailDraft;
import ai.mindconnect.mail.MailBody;
import ai.mindconnect.mail.MailAttachment;
import ai.mindconnect.mail.MailMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A mailbox over IMAP or POP3, with SMTP for sending — the
 * {@code mc-agent-tools-email} machinery behind {@link MailStore}.
 *
 * <p><b>One connection per call, not one per store.</b> The tool module made
 * that choice for a reason that holds here too: a pooled IMAP connection has
 * to stay honest across a password change and a server that drops it while
 * idle, and a connect is a round-trip, not a session. A screen that shows a
 * folder makes one; the cost is visible and bounded.
 *
 * <p>The exception here is {@link #list}, which opens the folder once and
 * searches it twice — see there.
 */
public final class ImapMailStore implements MailStore {

    private final MailAccount account;

    public ImapMailStore(MailAccount account) {
        this.account = Objects.requireNonNull(account, "account");
    }

    @Override
    public List<MailFolder> folders() {
        try {
            List<String> names = Mailbox.folders(account);
            List<MailFolder> folders = new ArrayList<>(names.size());
            for (String name : names) {
                folders.add(MailFolder.of(name, displayName(name)));
            }
            folders.sort(inboxFirst());
            return folders;
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    /**
     * Free text is matched against subject <em>and</em> sender, which IMAP
     * cannot do in one SEARCH: its terms are ANDed. So the folder is opened
     * once and searched twice, and the two answers are merged by id — two
     * searches on one connection rather than one search that finds half of
     * what the user meant.
     */
    @Override
    public MailPage list(String folderId, int skip, int limit, MailQuery query) {
        boolean unreadOnly = query.unreadOnly();
        String search = query.search();
        String from = query.from();
        String subject = query.subject();
        java.time.Instant since = query.since();
        java.time.Instant before = query.before();
        try (Mailbox mailbox = Mailbox.open(account, folderId, false)) {
            if (search == null) {
                // The plain case, and the only one with an exact total: SEARCH
                // answers with message numbers, so the page and how many
                // matched in all come back from one round trip.
                Mailbox.Page found = mailbox.list(limit, skip, unreadOnly, from, subject, since, before);
                return MailPage.of(found.messages(), found.matching());
            }
            // IMAP ANDs its search terms, so one SEARCH for a word in both the
            // sender and the subject finds only messages that have it in both
            // — never what a person means. Two searches on one open folder,
            // merged by id.
            Map<String, MailMessage> merged = new LinkedHashMap<>();
            // The word in the subject (with the sender filter, if any), then in
            // the sender (with the subject filter); a filter on the field the
            // word already takes is checked afterwards.
            for (MailMessage message : mailbox.list(skip + limit, 0, unreadOnly, from, search, since, before)
                    .messages()) {
                if (contains(message.subject(), subject)) merged.put(message.id(), message);
            }
            for (MailMessage message : mailbox.list(skip + limit, 0, unreadOnly, search, subject, since, before)
                    .messages()) {
                if (contains(message.from(), from)) merged.putIfAbsent(message.id(), message);
            }
            List<MailMessage> page = merged.values().stream()
                    .sorted(Comparator.comparing(MailMessage::receivedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .skip(skip)
                    .limit(limit)
                    .toList();
            // No total for a search. The two counts would double-count every
            // message that matches both halves, and finding the overlap means
            // fetching everything — which is the cost paging exists to avoid.
            // MailPage.uncounted says so, and the pager offers one more page
            // at a time instead of inventing a last one.
            return MailPage.uncounted(page);
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    /** Case-insensitive "contains", true when there is nothing to look for. */
    private static boolean contains(String text, String wanted) {
        if (wanted == null) return true;
        return text != null && text.toLowerCase(java.util.Locale.ROOT)
                .contains(wanted.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public MailMessage read(String folderId, String messageId) {
        try (Mailbox mailbox = Mailbox.open(account, folderId, false)) {
            return mailbox.read(messageId, MAX_BODY_CHARS);
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public MailBody body(String folderId, String messageId) {
        try (Mailbox mailbox = Mailbox.open(account, folderId, false)) {
            MailText.Extract extract = mailbox.body(messageId);
            return new MailBody(extract.html(), extract.text());
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public List<MailAttachment> attachments(String folderId, String messageId) {
        try (Mailbox mailbox = Mailbox.open(account, folderId, false)) {
            return mailbox.attachments(messageId).stream()
                    .map(a -> new MailAttachment(String.valueOf(a.index()), a.name(), a.contentType(), a.size()))
                    .toList();
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public MailFile attachment(String folderId, String messageId, String attachmentId) {
        int index;
        try {
            index = Integer.parseInt(attachmentId);
        } catch (NumberFormatException e) {
            throw new MailStoreException("There is no attachment \"" + attachmentId + "\" on that message.");
        }
        try (Mailbox mailbox = Mailbox.open(account, folderId, false)) {
            MailText.AttachedFile file = mailbox.attachment(messageId, index);
            return new MailFile(file.name(), file.contentType(), file.data());
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public boolean canOrganise() {
        // POP3 has one folder and no notion of moving between folders.
        return !account.isPop3();
    }

    /**
     * Both of these open the folder once and work through the ids, because
     * the one expensive part is the connection: deleting eight messages as
     * eight connections is eight IMAP handshakes for eight flag changes.
     */
    @Override
    public List<String> delete(String folderId, List<String> messageIds) {
        if (!canOrganise()) {
            throw new MailStoreException("POP3 has no wastebasket to move a message to, and this "
                    + "screen deletes nothing for good. Change that mailbox to imap under Connections "
                    + "if the server offers it.");
        }
        if (messageIds == null || messageIds.isEmpty()) return List.of();
        String trash = Mailbox.trash(account);
        if (trash == null) {
            throw new MailStoreException("This mailbox has no wastebasket folder, and this screen "
                    + "deletes nothing for good. Move the messages to a folder instead.");
        }
        // A message moved to another folder gets a new UID there, so the
        // receipt is its Message-ID header — what Undo finds it by in Trash.
        List<String> receipt = new java.util.ArrayList<>();
        try (Mailbox mailbox = Mailbox.open(account, folderId, true)) {
            if (mailbox.folderName().equals(trash)) {
                throw new MailStoreException("These are already in the wastebasket. Empty it in your "
                        + "mail provider's own client — this screen deletes nothing for good.");
            }
            for (String id : messageIds) {
                String header = mailbox.messageIdHeader(id);
                mailbox.move(id, trash);
                if (header != null) receipt.add(header);
            }
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
        return receipt;
    }

    @Override
    public void restore(String folderId, List<String> receipt) {
        if (receipt == null || receipt.isEmpty()) return;
        String trash = Mailbox.trash(account);
        if (trash == null) return;
        try (Mailbox mailbox = Mailbox.open(account, trash, true)) {
            String target = folderId == null || folderId.isBlank() ? "INBOX" : folderId;
            for (String header : receipt) {
                mailbox.moveByMessageIdHeader(header, target);
            }
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public void move(String folderId, List<String> messageIds, String targetFolderId) {
        if (targetFolderId == null || targetFolderId.isBlank()) {
            throw new MailStoreException("Say which folder to move them to.");
        }
        organise(folderId, messageIds, (mailbox, id) -> mailbox.move(id, targetFolderId));
    }

    private void organise(String folderId, List<String> messageIds,
                          java.util.function.BiConsumer<Mailbox, String> what) {
        if (!canOrganise()) {
            throw new MailStoreException("POP3 has one folder and nothing to move between. "
                    + "Change that mailbox to imap under Connections if the server offers it.");
        }
        if (messageIds == null || messageIds.isEmpty()) return;
        try (Mailbox mailbox = Mailbox.open(account, folderId, true)) {
            for (String id : messageIds) {
                what.accept(mailbox, id);
            }
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public void setSeen(String folderId, String messageId, boolean seen) {
        if (account.isPop3()) {
            throw new MailStoreException("POP3 keeps no read flags, so this mailbox cannot mark "
                    + "a message as read. IMAP can — change the protocol on the connection if the "
                    + "server offers it.");
        }
        try (Mailbox mailbox = Mailbox.open(account, folderId, true)) {
            mailbox.setSeen(messageId, seen);
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public boolean canSend() {
        return account.canSend();
    }

    @Override
    public String send(MailDraft draft) {
        try {
            return MailSender.send(account, draft.to(), draft.cc(), draft.subject(),
                    draft.isHtml() ? draft.html() : draft.body(), draft.isHtml(),
                    draft.attachments().stream()
                            .map(f -> new MailSender.Attachment(f.name(), f.contentType(), f.data()))
                            .toList());
        } catch (MailAccessException | MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // Nothing is held: every call above opens and closes its own.
    }

    /** {@code INBOX.Gesendet} reads as "Gesendet"; the full path stays the id. */
    private static String displayName(String path) {
        int cut = Math.max(path.lastIndexOf('.'), path.lastIndexOf('/'));
        return cut < 0 || cut == path.length() - 1 ? path : path.substring(cut + 1);
    }

    private static Comparator<MailFolder> inboxFirst() {
        return Comparator.comparing((MailFolder f) -> "INBOX".equalsIgnoreCase(f.id()) ? 0 : 1)
                .thenComparing(MailFolder::name, String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * How much of a body the reading pane gets. Generous — a person reads the
     * whole mail, unlike a model, which is why the tool module's default is a
     * tenth of this.
     */
    static final int MAX_BODY_CHARS = 200_000;
}
