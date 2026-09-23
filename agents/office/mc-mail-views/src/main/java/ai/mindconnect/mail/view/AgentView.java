package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Fetched;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.MailStoreException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an agent gathered: exactly these messages, in this order, from
 * wherever they lie.
 *
 * <p>Stands rather than lives — the rows do not change on their own. A row
 * can be taken out without touching the message; a moved message stays,
 * under its new location and id; one that is not there any more is taken
 * out when the view is read, and the page says so. Held as references, not
 * messages: the rows are read fresh each time, which is what makes the
 * read flag and the subject right.
 */
public final class AgentView implements MailListView {

    public static final String KIND = "agent";

    /**
     * A bound, not a page: a list is references, read a page at a time, so
     * "the five hundred newsletters" is an ordinary answer. Past this it is
     * the folder itself, and a folder is a view of its own.
     */
    public static final int MAX = 5_000;

    /** {@link ViewState#extra} key: the view it was gathered from. The chat is {@link MailListView#SESSION}. */
    public static final String FROM = "from";

    private static final String ENTRIES = "entries";

    private final ViewId id;
    private final UserId owner;
    private final String title;
    private final List<MailMessage.Ref> entries;
    private final ViewState state;
    private final MailAccounts accounts;

    AgentView(ViewId id, UserId owner, String title, List<MailMessage.Ref> entries, ViewState state,
              MailAccounts accounts) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.title = title == null || title.isBlank() ? "Found" : title.strip();
        this.entries = entries == null ? List.of() : List.copyOf(entries);
        this.state = state == null ? ViewState.EMPTY : state;
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    /** A new, empty one for a chat to gather into. */
    public static AgentView empty(UserId owner, String sessionId, ViewId from, MailAccounts accounts) {
        ViewState state = ViewState.EMPTY.with(SESSION, sessionId).with(FROM, from == null ? null : from.value());
        return new AgentView(ViewId.saved(), owner, null, List.of(), state, accounts);
    }

    @Override public ViewId id() { return id; }
    @Override public String kind() { return KIND; }
    @Override public UserId owner() { return owner; }
    @Override public String title() { return title; }
    @Override public ViewState state() { return state; }
    @Override public boolean lives() { return false; }
    @Override public boolean canRemove() { return true; }

    /** The account it was gathered from, when that was one account's folder — for the sidebar and the composer. */
    @Override
    public String account() {
        ViewId from = from();
        return from == null ? null : from.account();
    }

    public List<MailMessage.Ref> entries() { return entries; }
    public int size() { return entries.size(); }
    public boolean full() { return entries.size() >= MAX; }

    /** The view the chat was opened on — where "back" leads. */
    @Override
    public ViewId from() {
        String from = state.extra(FROM);
        return from == null ? null : ViewId.of(from);
    }

    @Override
    public Map<String, String> data() {
        StringBuilder lines = new StringBuilder();
        for (MailMessage.Ref ref : entries) {
            lines.append(ref.location().account()).append('\t').append(ref.location().folderId())
                    .append('\t').append(ref.id()).append('\n');
        }
        return Map.of(ENTRIES, lines.toString());
    }

    @Override
    public ListPage page(int offset, int limit) {
        List<MailMessage.Ref> slice = entries.stream().skip(offset).limit(limit).toList();
        Map<String, Fetched<MailMessage>> found = read(slice);
        List<MailItem> items = new ArrayList<>(slice.size());
        List<String> gone = new ArrayList<>();
        for (MailMessage.Ref ref : slice) {
            Fetched<MailMessage> fetched = found.get(ref.rowId());
            if (fetched == null) {
                gone.add(ref.rowId());
            } else {
                items.add(MailItem.of(fetched, state.selected().contains(ref.rowId())));
            }
        }
        return new ListPage(items, entries.size() - gone.size(), true, Instant.now(), gone);
    }

    /** One connection per mailbox and folder, however the rows are scattered. */
    private Map<String, Fetched<MailMessage>> read(List<MailMessage.Ref> refs) {
        Map<Location, List<String>> perFolder = new LinkedHashMap<>();
        for (MailMessage.Ref ref : refs) {
            perFolder.computeIfAbsent(ref.location(), k -> new ArrayList<>()).add(ref.id());
        }
        Map<String, Fetched<MailMessage>> found = new LinkedHashMap<>();
        for (Map.Entry<Location, List<String>> group : perFolder.entrySet()) {
            try (MailStore store = accounts.open(owner, group.getKey().account())) {
                for (MailMessage message : store.summaries(group.getKey().folderId(), group.getValue())) {
                    found.put(message.ref().rowId(), Fetched.live(message));
                }
            } catch (MailStoreException e) {
                // That mailbox does not answer; its rows are not gone, they are unread this time.
                for (String id : group.getValue()) {
                    found.put(new MailMessage.Ref(group.getKey(), id).rowId(), null);
                }
            }
        }
        found.values().removeIf(Objects::isNull);
        return found;
    }

    @Override
    public Location location(String rowId) {
        for (MailMessage.Ref ref : entries) {
            if (ref.rowId().equals(rowId)) return ref.location();
        }
        throw new IllegalArgumentException("\"" + rowId + "\" is not in this list.");
    }

    @Override
    public MailListView without(Collection<String> rowIds) {
        Set<String> out = new HashSet<>(rowIds);
        List<MailMessage.Ref> left = entries.stream().filter(ref -> !out.contains(ref.rowId())).toList();
        return new AgentView(id, owner, title, left, state.untick(rowIds), accounts);
    }

    /** Moved rows stay — under their new location and id; the ticks follow. */
    @Override
    public MailListView afterMove(Map<String, MailMessage.Ref> moved) {
        List<MailMessage.Ref> after = new ArrayList<>(entries.size());
        Map<String, String> renamed = new LinkedHashMap<>();
        for (MailMessage.Ref ref : entries) {
            MailMessage.Ref now = moved.get(ref.rowId());
            if (now == null) {
                after.add(ref);
            } else {
                after.add(now);
                renamed.put(ref.rowId(), now.rowId());
            }
        }
        return new AgentView(id, owner, title, after, state.renamed(renamed), accounts);
    }

    /** More rows, in the order given; what is already there stays where it was. At most {@link #MAX}. */
    public AgentView add(Collection<MailMessage.Ref> more) {
        List<MailMessage.Ref> all = new ArrayList<>(entries);
        Set<String> have = new HashSet<>();
        for (MailMessage.Ref ref : entries) have.add(ref.rowId());
        for (MailMessage.Ref ref : more) {
            if (all.size() >= MAX) break;
            if (have.add(ref.rowId())) all.add(ref);
        }
        return new AgentView(id, owner, title, all, state, accounts);
    }

    public AgentView titled(String title) {
        return new AgentView(id, owner, title, entries, state, accounts);
    }

    @Override
    public MailListView withState(ViewState state) {
        return new AgentView(id, owner, title, entries, state, accounts);
    }

    public static final class Factory implements MailListViewFactory {

        private final MailAccounts accounts;

        public Factory(MailAccounts accounts) {
            this.accounts = Objects.requireNonNull(accounts, "accounts");
        }

        @Override public String kind() { return KIND; }

        /** Only ever in the store: the id says nothing about what is in it. */
        @Override public boolean derives(ViewId id) { return false; }

        @Override
        public MailListView open(UserId user, ViewId id, StoredView stored) {
            List<MailMessage.Ref> entries = new ArrayList<>();
            String lines = stored == null ? null : stored.data().get(ENTRIES);
            if (lines != null) {
                for (String line : lines.split("\n")) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length == 3 && !parts[2].isBlank()) {
                        entries.add(new MailMessage.Ref(new Location(parts[0], parts[1]), parts[2]));
                    }
                }
            }
            return new AgentView(id, user, stored == null ? null : stored.title(), entries,
                    stored == null ? ViewState.EMPTY : stored.state(), accounts);
        }
    }
}
