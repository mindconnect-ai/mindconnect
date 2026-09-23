package ai.mindconnect.office.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.mail.ConnectedMailbox;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.MailFolder;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.MailStoreException;
import ai.mindconnect.mail.view.AgentView;
import ai.mindconnect.mail.view.CurrentView;
import ai.mindconnect.mail.view.ListPage;
import ai.mindconnect.mail.view.MailItem;
import ai.mindconnect.mail.view.MailListView;
import ai.mindconnect.mail.view.MailListViews;
import ai.mindconnect.mail.view.NoSuchViewException;
import ai.mindconnect.mail.view.StoredView;
import ai.mindconnect.mail.view.ViewId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.mindconnect.office.tools.OfficeTool.object;
import static ai.mindconnect.office.tools.OfficeTool.props;
import static ai.mindconnect.office.tools.OfficeTool.str;
import static ai.mindconnect.office.tools.OfficeTool.string;
import static ai.mindconnect.office.tools.OfficeTool.strings;

/**
 * How an agent works with the list on the person's screen: it gathers what
 * it finds into a list of its own ({@code mail_list_add},
 * {@code mail_list_remove}), puts a list on the screen
 * ({@code mail_list_show}) and asks what is on it ({@code mail_view_current}).
 *
 * <p>The gathering is the point. Finding two hundred advertising mails takes
 * a dozen pages, and by the last one the ids from the first are no longer
 * in the model's memory — so a tool that took the whole list as an argument
 * showed the last page and called it the answer. Here every page is put
 * aside as it is found, into an {@link AgentView} that belongs to the chat
 * and outlives it, and showing it needs no ids at all.
 *
 * <p>What is on the screen is server state ({@link CurrentView} and the
 * view's own {@link ai.mindconnect.mail.view.ViewState}), so the agent reads
 * the same selection and search the person sees — no form fields to harvest.
 */
final class MailListTools {

    static final String ADD = "mail_list_add";
    static final String REMOVE = "mail_list_remove";
    static final String SHOW = "mail_list_show";
    static final String CURRENT = "mail_view_current";

    static final Set<String> NAMES = Set.of(ADD, REMOVE, SHOW, CURRENT);

    /** How the show tool names the view it put on the screen — the last line of its answer, for the screen's card. */
    static final String VIEW_MARK = "(view ";

    private final MailAccounts mail;
    private final MailListViews views;
    private final CurrentView current;

    MailListTools(MailAccounts mail, MailListViews views, CurrentView current) {
        this.mail = mail;
        this.views = views;
        this.current = current;
    }

    Optional<Tool> create(String name, UserId user, String sessionId) {
        Accounts<ConnectedMailbox> accounts = new Accounts<>(mail.of(user), ConnectedMailbox::id,
                ConnectedMailbox::key, ConnectedMailbox::describe, ConnectedMailbox::usable);
        return Optional.ofNullable(switch (name) {
            case ADD -> add(user, sessionId, accounts);
            case REMOVE -> remove(user, sessionId);
            case SHOW -> show(user, sessionId);
            case CURRENT -> whatIsOnTheScreen(user);
            default -> null;
        });
    }

    // ── the chat's own list ─────────────────────────────────────────────────

    /** The list this chat gathers into — found by the chat's id among the saved ones, or made now. */
    private AgentView listOf(UserId user, String sessionId) {
        if (sessionId != null) {
            for (StoredView stored : views.saved(user, AgentView.KIND)) {
                if (sessionId.equals(stored.state().extra(MailListView.SESSION))) {
                    return (AgentView) views.open(user, stored.id());
                }
            }
        }
        return AgentView.empty(user, sessionId, current.current(user).orElse(null), mail);
    }

    private Tool add(UserId user, String sessionId, Accounts<ConnectedMailbox> accounts) {
        Map<String, Object> message = object(props(
                "id", string("The message id from mail_list."),
                "folder", string("The folder mail_list gave for it; the inbox when omitted."),
                "account", string("The account mail_list gave for it (provider.key); the open one when omitted.")),
                "id");
        return new OfficeTool(ADD,
                "Put messages aside for the list on the user's screen. Call it after every page of mail_list, "
                        + "with the messages of that page that belong to what was asked — you do not have to "
                        + "remember them afterwards, and they are not shown yet. Pass each message as mail_list "
                        + "gave it: its id, and its folder and account. Adding one twice is harmless. At most "
                        + AgentView.MAX + " are kept.",
                object(props("messages", Map.of("type", "array", "items", message,
                        "description", "The messages of this page that belong in the list.")), "messages"),
                args -> {
                    List<MailMessage.Ref> refs = refs(args, user, accounts);
                    if (refs.isEmpty()) throw new OfficeTool.Refused("Name at least one message, as mail_list gave it.");
                    AgentView list = listOf(user, sessionId);
                    boolean wasFull = list.full();
                    AgentView now = list.add(refs);
                    views.save(now);
                    if (wasFull || now.full()) {
                        return now.size() + " in the list — that is the most it holds. Stop searching and call "
                                + SHOW + ", or narrow down what you are looking for.";
                    }
                    return now.size() + " in the list now. Keep going, or call " + SHOW + " when you have them all.";
                });
    }

    private Tool remove(UserId user, String sessionId) {
        return new OfficeTool(REMOVE,
                "Take messages out of the list again — \"all the advertising except the two newsletters I "
                        + "read\". Name their ids. An id that is not in the list is quietly ignored.",
                object(props("ids", strings("The message ids to take out.")), "ids"),
                args -> {
                    List<String> ids = OfficeTool.list(args, "ids");
                    if (ids.isEmpty()) throw new OfficeTool.Refused("Name at least one id to take out.");
                    AgentView list = listOf(user, sessionId);
                    Set<String> rows = rowsOf(list, ids);
                    AgentView now = (AgentView) list.without(rows);
                    views.save(now);
                    return (list.size() - now.size()) + " taken out, " + now.size() + " left in the list.";
                });
    }

    private Tool show(UserId user, String sessionId) {
        return new OfficeTool(SHOW,
                "Show a list in the mail list on the user's screen, instead of the folder they are looking at "
                        + "— this is what makes \"show me …\", \"filter …\", \"which mails …\" actually happen. "
                        + "Without \"list\" it shows what you gathered with " + ADD + ", as you gathered it; with "
                        + "\"list\" any list by its id — a folder's (\"f/email.freemail/Archiv\"), \"all\", or a "
                        + "saved one. The title says what the list is now, in the user's language: \"Invoices "
                        + "from Swisscom\", \"Unanswered since Monday\". The user can go back with one click; "
                        + "do not list the same messages again in your answer.",
                object(props(
                        "title", string("What the list shows now, in a few words."),
                        "list", string("A list id, to show one that is not the gathered list."))),
                args -> {
                    String named = str(args, "list");
                    MailListView view;
                    if (named != null) {
                        try {
                            view = views.open(user, ViewId.of(named.strip()));
                        } catch (NoSuchViewException | IllegalArgumentException e) {
                            throw new OfficeTool.Refused("There is no list \"" + named + "\".");
                        }
                    } else {
                        AgentView gathered = listOf(user, sessionId);
                        if (gathered.size() == 0) {
                            throw new OfficeTool.Refused("The list is empty. Search first, and put what you find "
                                    + "aside with " + ADD + ".");
                        }
                        String title = str(args, "title");
                        view = title == null ? gathered : gathered.titled(title);
                        views.save(view);
                    }
                    current.current(user, view.id());
                    long size = view instanceof AgentView a ? a.size() : views.page(view, 0, 1).total();
                    return "The list on the user's screen now shows " + (size < 0 ? "the messages" : "these " + size
                            + " messages") + ". Say in one short sentence what they are, or answer what was asked.\n"
                            + VIEW_MARK + view.id().value() + ")";
                });
    }

    // ── what is on the screen ───────────────────────────────────────────────

    private Tool whatIsOnTheScreen(UserId user) {
        return new OfficeTool(CURRENT,
                "What the user is looking at right now: which list, its search, which page, and which rows "
                        + "they have ticked — with the ids, so \"the ones I ticked\" is something you can act "
                        + "on. Nothing is shown to the user by this call.",
                object(props()),
                args -> {
                    ViewId id = current.current(user).orElse(null);
                    if (id == null) return "Nothing is on the screen yet — the user has not opened the mail list.";
                    MailListView view;
                    try {
                        view = views.open(user, id);
                    } catch (NoSuchViewException e) {
                        return "The list on the screen (" + id + ") no longer exists.";
                    }
                    StringBuilder out = new StringBuilder();
                    out.append("On the screen: \"").append(view.title()).append("\" (").append(view.kind())
                            .append(", id ").append(view.id()).append(")");
                    if (view.state().query() != null) out.append(", searching for \"").append(view.state().query()).append('"');
                    out.append(", page ").append(view.state().page()).append('.');
                    if (view instanceof AgentView a) out.append(" It holds ").append(a.size()).append(" messages.");
                    Set<String> ticked = view.state().selected();
                    if (ticked.isEmpty()) {
                        out.append(" Nothing is ticked.");
                    } else {
                        out.append(" Ticked (").append(ticked.size()).append("): ");
                        int n = 0;
                        for (String row : ticked) {
                            if (n++ > 0) out.append(", ");
                            Location where = view.location(row);
                            out.append(row.substring(where.account().length() + 1))
                                    .append(" in ").append(where.folderId()).append(" of ").append(where.account());
                        }
                        out.append('.');
                    }
                    return out.toString();
                });
    }

    // ── arguments ───────────────────────────────────────────────────────────

    /** The messages named, as references — the account and folder filled in where the agent left them out. */
    private List<MailMessage.Ref> refs(Map<String, Object> args, UserId user, Accounts<ConnectedMailbox> accounts) {
        Object messages = args == null ? null : args.get("messages");
        if (!(messages instanceof List<?> list)) return List.of();
        List<MailMessage.Ref> refs = new ArrayList<>();
        Map<String, String> inboxes = new java.util.HashMap<>();
        for (Object entry : list) {
            String id;
            String account;
            String folder;
            if (entry instanceof Map<?, ?> m) {
                id = m.get("id") == null ? null : String.valueOf(m.get("id")).strip();
                account = m.get("account") == null ? null : String.valueOf(m.get("account")).strip();
                folder = m.get("folder") == null ? null : String.valueOf(m.get("folder")).strip();
            } else {
                id = entry == null ? null : String.valueOf(entry).strip();
                account = null;
                folder = null;
            }
            if (id == null || id.isEmpty()) continue;
            ConnectedMailbox box = accounts.one(account == null ? Map.of() : Map.of("account", account));
            if (folder == null || folder.isEmpty() || "inbox".equalsIgnoreCase(folder)) {
                folder = inboxes.computeIfAbsent(box.id(), key -> inboxOf(user, key));
            }
            refs.add(new MailMessage.Ref(new Location(box.id(), folder), id));
        }
        return refs;
    }

    private String inboxOf(UserId user, String account) {
        try (MailStore store = mail.open(user, account)) {
            List<MailFolder> folders = store.folders();
            return folders.isEmpty() ? "INBOX" : folders.get(0).id();
        } catch (MailStoreException e) {
            return "INBOX";
        }
    }

    /** The rows the agent means: it names message ids, the list is keyed by rows. */
    private static Set<String> rowsOf(AgentView list, List<String> ids) {
        Set<String> wanted = new HashSet<>(ids);
        Set<String> rows = new LinkedHashSet<>();
        for (MailMessage.Ref ref : list.entries()) {
            if (wanted.contains(ref.id()) || wanted.contains(ref.rowId())) rows.add(ref.rowId());
        }
        return rows;
    }

    /** The view id out of a show tool's answer, for the screen; null when the answer names none. */
    static ViewId shownIn(String result) {
        if (result == null) return null;
        int at = result.lastIndexOf(VIEW_MARK);
        if (at < 0) return null;
        int end = result.indexOf(')', at);
        return end < 0 ? null : ViewId.of(result.substring(at + VIEW_MARK.length(), end));
    }

    static String describe(ListPage page) {
        StringBuilder out = new StringBuilder();
        for (MailItem item : page.items()) {
            out.append("- ").append(item.rowId()).append(" · ").append(item.message().subject()).append('\n');
        }
        return out.toString();
    }
}
