package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.MailAccounts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where a name becomes a view: the store for what was kept, the factories
 * for what a kind means.
 *
 * <p>Opening goes one of two ways and both end in a factory. A name the
 * store knows is read back through the factory its record names. A name
 * the store does not know is offered to every factory, and the one that
 * can build a view from the name alone does — a folder's, all inboxes'.
 * Neither the store knows the kinds nor the factories the store.
 */
public final class MailListViews {

    private final ViewStore store;
    private final Map<String, MailListViewFactory> factories = new LinkedHashMap<>();

    public MailListViews(ViewStore store, List<MailListViewFactory> factories) {
        this.store = Objects.requireNonNull(store, "store");
        for (MailListViewFactory factory : factories) this.factories.put(factory.kind(), factory);
    }

    /** The three kinds that ship: a folder, all inboxes, what an agent gathered. */
    public static MailListViews standard(MailAccounts accounts, ViewStore store) {
        return standard(accounts, store, null);
    }

    /** The three kinds, reading through {@code index} where it has a window; null reads the provider. */
    public static MailListViews standard(MailAccounts accounts, ViewStore store, ai.mindconnect.mail.index.MailIndex index) {
        return new MailListViews(store, List.of(
                new FolderView.Factory(accounts, index),
                new AllInboxesView.Factory(accounts, index),
                new AgentView.Factory(accounts, index)));
    }

    /** Another kind — a module's own view, registered by the host. */
    public MailListViews register(MailListViewFactory factory) {
        factories.put(factory.kind(), factory);
        return this;
    }

    public MailListView open(UserId user, ViewId id) {
        StoredView stored = store.load(user, id).orElse(null);
        if (stored != null) {
            MailListViewFactory factory = factories.get(stored.kind());
            if (factory == null) throw new NoSuchViewException(id);
            return factory.open(user, id, stored);
        }
        for (MailListViewFactory factory : factories.values()) {
            if (factory.derives(id)) return factory.open(user, id, null);
        }
        throw new NoSuchViewException(id);
    }

    public void save(MailListView view) {
        store.save(StoredView.of(view));
    }

    public void delete(UserId user, ViewId id) {
        store.delete(user, id);
    }

    /** The user's kept views of one kind, newest first — for a sidebar. */
    public List<StoredView> saved(UserId user, String kind) {
        return store.list(user, kind);
    }

    /**
     * A page of a view — and the housekeeping that goes with it: rows the
     * view could not find any more are taken out of it and saved away, so
     * the next page does not look for them again. The page says which.
     */
    public ListPage page(MailListView view, int offset, int limit) {
        ListPage page = view.page(offset, limit);
        if (!page.gone().isEmpty() && view.canRemove()) {
            save(view.without(page.gone()));
        }
        return page;
    }
}
