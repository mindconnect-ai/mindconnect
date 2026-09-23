package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.ConnectedMailbox;
import ai.mindconnect.mail.Fetched;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailQuery;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.index.MailIndex;
import ai.mindconnect.mail.MailStoreException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbox of every connected mailbox, as one list, newest first.
 *
 * <p>The newest message can be in any account, so a page here is the first
 * {@code offset + limit} of every inbox, merged and cut — which is why this
 * view is the one that most wants the index of concept 40. A mailbox that
 * does not answer is left out of the page rather than failing it; the
 * others are still worth showing.
 */
public final class AllInboxesView implements MailListView {

    public static final String KIND = "all-inboxes";

    private final UserId owner;
    private final ViewState state;
    private final MailAccounts accounts;
    private final MailIndex index;

    AllInboxesView(UserId owner, ViewState state, MailAccounts accounts) {
        this(owner, state, accounts, null);
    }

    AllInboxesView(UserId owner, ViewState state, MailAccounts accounts, MailIndex index) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.state = state == null ? ViewState.EMPTY : state;
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.index = index;
    }

    @Override public ViewId id() { return ViewId.allInboxes(); }
    @Override public String kind() { return KIND; }
    @Override public UserId owner() { return owner; }
    @Override public ViewState state() { return state; }
    @Override public Map<String, String> data() { return Map.of(); }
    @Override public boolean lives() { return true; }
    @Override public boolean canRemove() { return false; }
    @Override public MailListView without(Collection<String> rowIds) { return this; }

    @Override
    public String title() {
        return state.query() == null ? "All inboxes" : "“" + state.query() + "” in all inboxes";
    }

    @Override
    public ListPage page(int offset, int limit) {
        MailQuery query = MailQuery.of(state.flag(FolderView.UNREAD_ONLY), state.query());
        List<Fetched<MailMessage>> merged = new ArrayList<>();
        long total = 0;
        boolean counted = true;
        Instant asOf = Instant.now();
        for (ConnectedMailbox mailbox : accounts.of(owner)) {
            if (!mailbox.usable()) continue;
            try (MailStore store = accounts.open(owner, mailbox.id())) {
                String inbox = inboxOf(store);
                if (index != null) {
                    MailIndex.Slice slice = index.page(owner, store, new Location(mailbox.id(), inbox), 0, offset + limit, query);
                    merged.addAll(slice.fetched());
                    if (slice.counted()) total += slice.total(); else counted = false;
                    if (slice.asOf().isBefore(asOf)) asOf = slice.asOf();
                } else {
                    MailPage page = store.list(inbox, 0, offset + limit, query);
                    merged.addAll(page.fetched());
                    if (page.counted() && !page.estimate()) total += page.total(); else counted = false;
                    if (page.asOf().isBefore(asOf)) asOf = page.asOf();
                }
            } catch (MailStoreException e) {
                counted = false;   // one account short: the page is still worth showing
            }
        }
        merged.sort(Comparator.comparing((Fetched<MailMessage> f) -> f.value().receivedAt(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<MailItem> items = new ArrayList<>(limit);
        for (Fetched<MailMessage> fetched : merged.stream().skip(offset).limit(limit).toList()) {
            items.add(MailItem.of(fetched, state.selected().contains(fetched.value().ref().rowId())));
        }
        return ListPage.of(items, counted ? total : -1, counted, asOf);
    }

    /**
     * A row of this view lies in the inbox of the account its id names. The
     * account is the part of the row id before the first colon — the one
     * place this is read back, and safe because this view wrote it there
     * and no account id has a colon in it.
     */
    @Override
    public Location location(String rowId) {
        int colon = rowId.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("\"" + rowId + "\" is not a row of All inboxes.");
        String account = rowId.substring(0, colon);
        try (MailStore store = accounts.open(owner, account)) {
            return new Location(account, inboxOf(store));
        }
    }

    /** The inbox: the first folder, by the port's contract. */
    private static String inboxOf(MailStore store) {
        var folders = store.folders();
        return folders.isEmpty() ? "INBOX" : folders.get(0).id();
    }

    @Override
    public MailListView withState(ViewState state) {
        return new AllInboxesView(owner, state, accounts, index);
    }

    public static final class Factory implements MailListViewFactory {

        private final MailAccounts accounts;
        private final MailIndex index;

        public Factory(MailAccounts accounts) {
            this(accounts, null);
        }

        public Factory(MailAccounts accounts, MailIndex index) {
            this.index = index;
            this.accounts = Objects.requireNonNull(accounts, "accounts");
        }

        @Override public String kind() { return KIND; }

        @Override public boolean derives(ViewId id) { return id.isAllInboxes(); }

        @Override
        public MailListView open(UserId user, ViewId id, StoredView stored) {
            return new AllInboxesView(user, stored == null ? ViewState.EMPTY : stored.state(), accounts, index);
        }
    }
}
