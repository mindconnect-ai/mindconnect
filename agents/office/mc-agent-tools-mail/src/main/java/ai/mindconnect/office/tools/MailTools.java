package ai.mindconnect.office.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.Outcome;
import ai.mindconnect.mail.ConnectedMailbox;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.MailDraft;
import ai.mindconnect.mail.MailFolder;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailQuery;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.MailStoreException;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.mindconnect.office.tools.OfficeTool.*;

/**
 * The mail tools: one set for every mailbox the user connected — IMAP,
 * Outlook, Gmail — named by account, or {@code all} for the listing.
 *
 * <p>Reading and changing are separate tools on purpose. Approval is set per
 * tool name on an agent's binding, so {@code mail_send}, {@code mail_delete},
 * {@code mail_move} and {@code mail_mark_read} can each be bound to ask first
 * while listing and reading run freely.
 */
final class MailTools {

    static final String FOLDERS = "mail_folders";
    static final String LIST = "mail_list";
    static final String READ = "mail_read";
    static final String MARK_READ = "mail_mark_read";
    static final String MOVE = "mail_move";
    static final String DELETE = "mail_delete";
    static final String SEND = "mail_send";

    static final Set<String> NAMES = Set.of(FOLDERS, LIST, READ, MARK_READ, MOVE, DELETE, SEND);

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 200;

    /** Above this many rows a line drops its preview — the point is the ids, not the reading. */
    static final int BRIEF_ABOVE = 50;
    static final int DEFAULT_CHARS = 8000;

    /** How many words one call may look for; each is a search of its own at every provider. */
    static final int MAX_TERMS = 6;

    static final int MAX_CHARS = 40000;

    private final MailAccounts mail;
    private final ZoneId zone;

    MailTools(MailAccounts mail, ZoneId zone) {
        this.mail = mail;
        this.zone = zone;
    }

    Optional<Tool> create(String name, UserId user) {
        Accounts<ConnectedMailbox> accounts = new Accounts<>(mail.of(user), ConnectedMailbox::id,
                ConnectedMailbox::key, ConnectedMailbox::describe, ConnectedMailbox::usable);
        return Optional.ofNullable(switch (name) {
            case FOLDERS -> folders(user, accounts);
            case LIST -> listing(user, accounts);
            case READ -> read(user, accounts);
            case MARK_READ -> markRead(user, accounts);
            case MOVE -> move(user, accounts);
            case DELETE -> delete(user, accounts);
            case SEND -> send(user, accounts);
            default -> null;
        });
    }

    private static Map<String, Object> account(Accounts<?> accounts, boolean withAll) {
        return oneOf(accounts.choices(withAll), withAll
                ? "Which mailbox, as provider.key — or \"all\" for every one. Optional when there is only one."
                : "Which mailbox, as provider.key. Optional when there is only one.");
    }

    // ── reading ─────────────────────────────────────────────────────────────

    private Tool folders(UserId user, Accounts<ConnectedMailbox> accounts) {
        return new OfficeTool(FOLDERS,
                "List the folders of the user's mailboxes, with their ids and unread counts where the "
                        + "provider gives them. The ids are what the other mail tools take as \"folder\".",
                object(props("account", account(accounts, true))),
                args -> {
                    StringBuilder out = new StringBuilder();
                    for (ConnectedMailbox box : accounts.pick(args, true)) {
                        out.append("Folders of ").append(box.id()).append(" (").append(box.describe()).append("):\n");
                        try (MailStore store = mail.open(user, box.id())) {
                            for (MailFolder folder : store.folders()) {
                                out.append("- ").append(folder.id())
                                        .append(folder.id().equals(folder.name()) ? "" : " — " + folder.name())
                                        .append(folder.hasUnreadCount() && folder.unread() > 0
                                                ? " (" + folder.unread() + " unread)" : "")
                                        .append('\n');
                            }
                        } catch (MailStoreException e) {
                            out.append("  (did not answer: ").append(e.getMessage()).append(")\n");
                        }
                    }
                    return out.toString();
                });
    }

    private Tool listing(UserId user, Accounts<ConnectedMailbox> accounts) {
        return new OfficeTool(LIST,
                "List messages, newest first — headers and the start of the text, not the whole message. "
                        + "Narrow it with from, subject, text, since, before and unread_only; page with offset "
                        + "(the answer says how many matched, so you know whether to ask for the next page). "
                        + "With account \"all\" it lists every mailbox's inbox as one. Each line carries the id, "
                        + "account and folder that " + READ + " and the other tools take.",
                object(props(
                        "account", account(accounts, true),
                        "folder", string("A folder id or name from " + FOLDERS + "; the inbox when omitted. "
                                + "Only the inbox with \"all\"."),
                        "from", string("Only messages whose sender (name or address) contains this."),
                        "subject", string("Only messages whose subject contains this."),
                        "text", string("Words to look for in the sender or the subject. This is not a query "
                                + "language: write the words, or several separated by OR "
                                + "(\"newsletter OR Rabatt OR unsubscribe\") — each is searched on its own and "
                                + "the results are merged. At most " + MAX_TERMS + " of them."),
                        "since", string("Only messages received on or after this, as 2026-09-01."),
                        "before", string("Only messages received before this, as 2026-09-15."),
                        "unread_only", bool("Only messages not marked as read."),
                        "limit", integer("How many, at most " + MAX_LIMIT + " (default " + DEFAULT_LIMIT
                                + "). Above " + BRIEF_ABOVE + " the lines come without the preview text."),
                        "offset", integer("Skip this many of the newest matches first — for the next page. "
                                + "Page on your own until you have what was asked for; do not stop to ask."))),
                args -> {
                    List<ConnectedMailbox> boxes = accounts.pick(args, true);
                    int limit = Math.max(1, number(args, "limit", DEFAULT_LIMIT, MAX_LIMIT));
                    int offset = number(args, "offset", 0, 10_000);
                    List<String> terms = terms(str(args, "text"));
                    List<MailQuery> queries = new ArrayList<>();
                    for (String term : terms) {
                        queries.add(new MailQuery(flag(args, "unread_only", false), term,
                                str(args, "from"), str(args, "subject"),
                                instant(args, "since", zone), instant(args, "before", zone)));
                    }
                    boolean several = boxes.size() > 1 || queries.size() > 1;
                    // One search per word per mailbox; the same message found
                    // twice is one row, and the newest are the page.
                    java.util.LinkedHashMap<String, Row> found = new java.util.LinkedHashMap<>();
                    long matching = 0;
                    boolean counted = true;
                    List<String> silent = new ArrayList<>();
                    for (ConnectedMailbox box : boxes) {
                        try (MailStore store = mail.open(user, box.id())) {
                            List<MailFolder> folders = store.folders();
                            String folder = boxes.size() > 1 ? inbox(folders) : folder(folders, str(args, "folder"));
                            for (MailQuery query : queries) {
                                MailPage page = several
                                        ? store.list(folder, 0, offset + limit, query)
                                        : store.list(folder, offset, limit, query);
                                for (MailMessage m : page.messages()) {
                                    found.putIfAbsent(box.id() + "\u0000" + m.id(), new Row(box.id(), folder, m));
                                }
                                if (page.counted()) matching += page.total(); else counted = false;
                            }
                        } catch (MailStoreException e) {
                            if (boxes.size() == 1) throw new Refused(box.id() + " did not answer: " + e.getMessage());
                            silent.add(box.id());
                        }
                    }
                    List<Row> rows = new ArrayList<>(found.values());
                    if (several) {
                        rows = rows.stream()
                                .sorted(Comparator.comparing((Row r) -> r.message().receivedAt(),
                                        Comparator.nullsLast(Comparator.reverseOrder())))
                                .skip(offset).limit(limit).toList();
                    }
                    // Two words that find the same message would count it twice.
                    if (queries.size() > 1) counted = false;
                    StringBuilder out = new StringBuilder();
                    if (rows.isEmpty()) {
                        out.append(queries.get(0).isEmpty() ? "No messages." : "No message matches.")
                                .append('\n');
                    } else {
                        out.append("Messages ").append(offset + 1).append('–').append(offset + rows.size());
                        if (counted) out.append(" of ").append(matching);
                        out.append(boxes.size() > 1 ? ", all inboxes" : "").append(", newest first:\n\n");
                        boolean preview = rows.size() <= BRIEF_ABOVE;
                        for (Row row : rows) out.append(line(row, preview));
                        // The listing is for the model's eyes only. A tool that
                        // puts messages on the user's screen is a different call,
                        // and an answer that claims one without making it is a lie
                        // the person sees through at once.
                        out.append("\n(Read by you, not shown to the user.)\n");
                        if (counted && matching > offset + rows.size()) {
                            out.append("\n(").append(matching - offset - rows.size())
                                    .append(" more match — the next page is offset ")
                                    .append(offset + rows.size()).append(".)\n");
                        }
                    }
                    if (!silent.isEmpty()) out.append("\n(Did not answer: ").append(String.join(", ", silent)).append(")\n");
                    return out.toString();
                });
    }

    private Tool read(UserId user, Accounts<ConnectedMailbox> accounts) {
        return new OfficeTool(READ,
                "Read one message in full: sender, recipients, date, attachment names and the text.",
                object(props(
                        "account", account(accounts, false),
                        "folder", string("The folder it was listed in; the inbox when omitted."),
                        "id", string("The message id from " + LIST + "."),
                        "max_chars", integer("How much of the text, at most " + MAX_CHARS
                                + " (default " + DEFAULT_CHARS + ")."))),
                args -> {
                    ConnectedMailbox box = accounts.one(args);
                    String id = required(args, "id");
                    int max = Math.max(200, number(args, "max_chars", DEFAULT_CHARS, MAX_CHARS));
                    try (MailStore store = mail.open(user, box.id())) {
                        String folder = folder(store.folders(), str(args, "folder"));
                        MailMessage m = store.read(folder, id);
                        StringBuilder out = new StringBuilder(line(new Row(box.id(), folder, m), false));
                        if (m.to() != null && !m.to().isEmpty()) out.append("  to: ").append(String.join(", ", m.to())).append('\n');
                        if (m.attachments() != null && !m.attachments().isEmpty()) {
                            out.append("  attachments: ").append(String.join(", ", m.attachments())).append('\n');
                        } else if (m.hasAttachments()) {
                            out.append("  attachments: yes\n");
                        }
                        out.append('\n').append(cut(m.body(), max));
                        return out.toString();
                    }
                });
    }

    // ── changing ────────────────────────────────────────────────────────────

    private Tool markRead(UserId user, Accounts<ConnectedMailbox> accounts) {
        return new OfficeTool(MARK_READ,
                "Mark messages as read or unread.",
                object(props(
                        "account", account(accounts, false),
                        "folder", string("The folder they are in; the inbox when omitted."),
                        "ids", strings("The message ids."),
                        "read", bool("true to mark read (default), false to mark unread.")), "ids"),
                args -> {
                    ConnectedMailbox box = accounts.one(args);
                    List<String> ids = ids(args);
                    boolean read = flag(args, "read", true);
                    try (MailStore store = mail.open(user, box.id())) {
                        String folder = folder(store.folders(), str(args, "folder"));
                        for (String id : ids) store.setSeen(folder, id, read);
                    }
                    return ids.size() + (ids.size() == 1 ? " message" : " messages") + " marked "
                            + (read ? "read." : "unread.");
                });
    }

    private Tool move(UserId user, Accounts<ConnectedMailbox> accounts) {
        return new OfficeTool(MOVE,
                "Move messages to another folder of the same mailbox.",
                object(props(
                        "account", account(accounts, false),
                        "folder", string("The folder they are in now; the inbox when omitted."),
                        "ids", strings("The message ids."),
                        "to", string("The folder to move them to — an id or a name from " + FOLDERS + ".")),
                        "ids", "to"),
                args -> {
                    ConnectedMailbox box = accounts.one(args);
                    List<String> ids = ids(args);
                    try (MailStore store = mail.open(user, box.id())) {
                        if (!store.canOrganise()) throw new Refused(box.id() + " cannot move messages (POP3).");
                        List<MailFolder> folders = store.folders();
                        String from = folder(folders, str(args, "folder"));
                        String to = named(folders, required(args, "to"))
                                .orElseThrow(() -> new Refused(noSuchFolder(folders, str(args, "to"))));
                        return whatBecame(store.move(from, ids, to), "moved to " + to);
                    }
                });
    }

    private Tool delete(UserId user, Accounts<ConnectedMailbox> accounts) {
        return new OfficeTool(DELETE,
                "Delete messages: they go to the mailbox's deleted-items folder, from where the user can "
                        + "get them back.",
                object(props(
                        "account", account(accounts, false),
                        "folder", string("The folder they are in; the inbox when omitted."),
                        "ids", strings("The message ids.")), "ids"),
                args -> {
                    ConnectedMailbox box = accounts.one(args);
                    List<String> ids = ids(args);
                    try (MailStore store = mail.open(user, box.id())) {
                        if (!store.canOrganise()) throw new Refused(box.id() + " cannot delete messages (POP3).");
                        return whatBecame(store.delete(folder(store.folders(), str(args, "folder")), ids),
                                "moved to the deleted items of " + box.id());
                    }
                });
    }

    private Tool send(UserId user, Accounts<ConnectedMailbox> accounts) {
        return new OfficeTool(SEND,
                "Send an e-mail from one of the user's mailboxes. It leaves at once and cannot be called "
                        + "back — write exactly what the user asked for.",
                object(props(
                        "account", account(accounts, false),
                        "to", strings("Recipient addresses."),
                        "cc", strings("Addresses in copy."),
                        "subject", string("The subject line."),
                        "body", string("The text of the message."),
                        "html", string("The same message as simple HTML, when it should be formatted.")),
                        "to", "subject", "body"),
                args -> {
                    ConnectedMailbox box = accounts.one(args);
                    List<String> to = list(args, "to");
                    if (to.isEmpty()) throw new Refused("\"to\" needs at least one address.");
                    MailDraft draft = new MailDraft(to, list(args, "cc"), required(args, "subject"),
                            required(args, "body"), str(args, "html"));
                    try (MailStore store = mail.open(user, box.id())) {
                        if (!store.canSend()) throw new Refused(box.id() + " cannot send mail.");
                        store.send(draft);
                    }
                    return "Sent from " + box.id() + " to " + String.join(", ", to) + ".";
                });
    }

    // ── the parts ───────────────────────────────────────────────────────────

    /**
     * The words one call looks for. "This is not a query language" is the
     * whole point: a model that writes Gmail's {@code a OR b} had it searched
     * for as one string by IMAP and Graph, which find nothing — so the words
     * are split here and each is a search of its own.
     */
    static List<String> terms(String text) {
        if (text == null || text.isBlank()) return java.util.Collections.singletonList(null);
        List<String> terms = new ArrayList<>();
        for (String part : text.split("(?i)\\s+OR\\s+|\\s*\\|\\s*")) {
            String term = part.strip().replaceAll("^[\"']|[\"']$", "").strip();
            if (!term.isEmpty() && !terms.contains(term)) terms.add(term);
            if (terms.size() == MAX_TERMS) break;
        }
        return terms.isEmpty() ? java.util.Collections.singletonList(null) : terms;
    }

    /**
     * One sentence for a call that changed where messages are: how many it
     * did, which were not there any more, and what the provider refused. A
     * model told "3 moved" when one of the three was gone acts on a list that
     * is not the mailbox.
     */
    static String whatBecame(List<Outcome> outcomes, String did) {
        int done = Outcome.done(outcomes);
        StringBuilder out = new StringBuilder();
        out.append(done).append(done == 1 ? " message " : " messages ").append(did).append('.');
        List<String> gone = Outcome.gone(outcomes);
        if (!gone.isEmpty()) {
            out.append(gone.size() == 1 ? " One was" : " " + gone.size() + " were")
                    .append(" not there any more: ").append(String.join(", ", gone)).append('.');
        }
        for (String why : Outcome.failures(outcomes)) out.append(" Not done: ").append(why);
        return out.toString();
    }

    private record Row(String account, String folder, MailMessage message) { }

    private String line(Row row, boolean preview) {
        MailMessage m = row.message();
        StringBuilder out = new StringBuilder("- id ").append(m.id())
                .append(" · account ").append(row.account())
                .append(" · folder ").append(row.folder());
        if (!m.seen()) out.append("  [unread]");
        if (m.hasAttachments()) out.append("  [attachment]");
        out.append('\n')
                .append("  from: ").append(m.from()).append('\n')
                .append("  subject: ").append(m.subject()).append('\n');
        if (m.receivedAt() != null) out.append("  received: ").append(when(m.receivedAt(), zone)).append('\n');
        if (preview && m.body() != null && !m.body().isBlank()) {
            String text = m.body().strip().replaceAll("\\s+", " ");
            out.append("  preview: ").append(text.length() > 160 ? text.substring(0, 160) + "…" : text).append('\n');
        }
        return out.toString();
    }

    private static List<String> ids(Map<String, Object> args) {
        List<String> ids = list(args, "ids");
        if (ids.isEmpty() && str(args, "id") != null) ids = List.of(str(args, "id"));
        if (ids.isEmpty()) throw new Refused("\"ids\" needs at least one message id.");
        return ids;
    }

    /** A folder named by id or name (any case); the inbox when none is named. */
    static String folder(List<MailFolder> folders, String named) {
        // "inbox" is the inbox in every language — Outlook calls it
        // "Posteingang" in a German mailbox.
        if (named == null || "inbox".equalsIgnoreCase(named)) return inbox(folders);
        return named(folders, named).orElseThrow(() -> new Refused(noSuchFolder(folders, named)));
    }

    /**
     * What to say about a folder that is not there — with the folders that
     * are. Sending the caller off to another tool for a list this method is
     * holding costs a round trip to learn what could have been in the
     * sentence.
     */
    static String noSuchFolder(List<MailFolder> folders, String named) {
        StringBuilder out = new StringBuilder("There is no folder \"").append(named).append("\". ");
        if (folders.isEmpty()) return out.append("This mailbox names none.").toString();
        out.append(folders.size() == 1 ? "The one there is: " : "The ones there are: ");
        List<String> names = new ArrayList<>();
        for (MailFolder folder : folders) {
            // The id is what a tool takes; the name is what the person sees,
            // and it is worth saying only when it differs.
            names.add(folder.name().equals(folder.id()) ? folder.id()
                    : folder.id() + " (\"" + folder.name() + "\")");
            if (names.size() >= MAX_FOLDERS_NAMED) break;
        }
        out.append(String.join(", ", names));
        if (folders.size() > names.size()) {
            out.append(" and ").append(folders.size() - names.size()).append(" more — ")
                    .append(FOLDERS).append(" lists them all");
        }
        return out.append('.').toString();
    }

    /** A mailbox can have hundreds; a refusal is a sentence, not a listing. */
    static final int MAX_FOLDERS_NAMED = 30;

    static Optional<String> named(List<MailFolder> folders, String named) {
        for (MailFolder f : folders) if (f.id().equals(named)) return Optional.of(f.id());
        for (MailFolder f : folders) if (f.name().equalsIgnoreCase(named)) return Optional.of(f.id());
        return Optional.empty();
    }

    /** The folder called inbox, whatever the case, else the first. */
    static String inbox(List<MailFolder> folders) {
        for (MailFolder f : folders) {
            if ("inbox".equalsIgnoreCase(f.name()) || "inbox".equalsIgnoreCase(f.id())) return f.id();
        }
        return folders.isEmpty() ? "INBOX" : folders.get(0).id();
    }
}
