package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Fetched;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.MailFolder;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailQuery;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.index.MailIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One folder of one mailbox — the inbox, an archive, the wastebasket.
 *
 * <p>Lives: the rows are whatever the provider says is in the folder now,
 * narrowed by the search and the unread flag in the state. Nothing can be
 * taken out of it without touching the message, and a moved message is
 * simply not in this folder any more.
 */
public final class FolderView implements MailListView {

    public static final String KIND = "folder";

    /** {@link ViewState#extra} key: only unread rows. */
    public static final String UNREAD_ONLY = "unreadOnly";

    private final ViewId id;
    private final UserId owner;
    private final String account;
    private final String folderId;
    private final String folderName;
    private final boolean organises;
    private final ViewState state;
    private final MailAccounts accounts;
    /** The window index, when the host has one; null reads every page from the provider. */
    private final MailIndex index;

    FolderView(ViewId id, UserId owner, String account, String folderId, String folderName,
               boolean organises, ViewState state, MailAccounts accounts) {
        this(id, owner, account, folderId, folderName, organises, state, accounts, null);
    }

    FolderView(ViewId id, UserId owner, String account, String folderId, String folderName,
               boolean organises, ViewState state, MailAccounts accounts, MailIndex index) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.account = Objects.requireNonNull(account, "account");
        this.folderId = Objects.requireNonNull(folderId, "folderId");
        this.folderName = folderName == null ? folderId : folderName;
        this.organises = organises;
        this.state = state == null ? ViewState.EMPTY : state;
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.index = index;
    }

    @Override public ViewId id() { return id; }
    @Override public String kind() { return KIND; }
    @Override public UserId owner() { return owner; }
    @Override public ViewState state() { return state; }
    @Override public Map<String, String> data() { return Map.of(); }
    @Override public boolean lives() { return true; }
    @Override public boolean canRemove() { return false; }
    @Override public boolean organises() { return organises; }
    @Override public MailListView without(Collection<String> rowIds) { return this; }

    @Override public String account() { return account; }
    @Override public String folderId() { return folderId; }

    @Override
    public String title() {
        return state.query() == null ? folderName : "“" + state.query() + "” in " + folderName;
    }

    @Override
    public ListPage page(int offset, int limit) {
        MailQuery query = MailQuery.of(state.flag(UNREAD_ONLY), state.query());
        try (MailStore store = accounts.open(owner, account)) {
            List<Fetched<MailMessage>> fetched;
            long total;
            boolean counted;
            java.time.Instant asOf;
            if (index != null) {
                // The window answers; a page below it is read live and widens it.
                MailIndex.Slice slice = index.page(owner, store, new Location(account, folderId), offset, limit, query);
                fetched = slice.fetched();
                total = slice.total();
                counted = slice.counted();
                asOf = slice.asOf();
            } else {
                MailPage page = store.list(folderId, offset, limit, query);
                fetched = page.fetched();
                total = page.total();
                counted = page.counted() && !page.estimate();
                asOf = page.asOf();
            }
            List<MailItem> items = new ArrayList<>(fetched.size());
            for (Fetched<MailMessage> f : fetched) {
                items.add(MailItem.of(f, state.selected().contains(f.value().ref().rowId())));
            }
            return ListPage.of(items, total, counted, asOf);
        }
    }

    /** Every row of a folder lies in that folder. */
    @Override
    public Location location(String rowId) {
        return new Location(account, folderId);
    }

    @Override
    public MailListView withState(ViewState state) {
        return new FolderView(id, owner, account, folderId, folderName, organises, state, accounts, index);
    }

    /** Builds folder views from their id; the folder's name comes from the mailbox. */
    public static final class Factory implements MailListViewFactory {

        private final MailAccounts accounts;
        private final MailIndex index;

        public Factory(MailAccounts accounts) {
            this(accounts, null);
        }

        public Factory(MailAccounts accounts, MailIndex index) {
            this.accounts = Objects.requireNonNull(accounts, "accounts");
            this.index = index;
        }

        @Override public String kind() { return KIND; }

        @Override public boolean derives(ViewId id) { return id.isFolder(); }

        @Override
        public MailListView open(UserId user, ViewId id, StoredView stored) {
            String account = id.account();
            String folderId = id.folderId();
            if (account == null || folderId == null) throw new NoSuchViewException(id);
            String name = folderId;
            boolean organises = true;
            try (MailStore store = accounts.open(user, account)) {
                for (MailFolder folder : store.folders()) {
                    if (folder.id().equals(folderId)) { name = folder.name(); break; }
                }
                organises = store.canOrganise();
            }
            return new FolderView(id, user, account, folderId, name, organises,
                    stored == null ? ViewState.EMPTY : stored.state(), accounts, index);
        }
    }
}
