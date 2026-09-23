package ai.mindconnect.mail.view;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a person has done to a view that should still be so when they come
 * back: which rows are ticked, what is in the search box, which page they
 * are on — and whatever else a kind of view needs to remember, in
 * {@code extra}, so a new kind does not need a new field here.
 *
 * <p>Kept with the view on the server, not in the browser. That is what lets
 * the search survive the pager, the ticks survive a trip to the calendar,
 * and an agent read the same selection the screen shows.
 *
 * @param selected the ticked rows, by {@link ai.mindconnect.mail.MailMessage.Ref#rowId()}
 * @param query    the search, or null for none
 * @param page     the page they are on, from 1
 * @param extra    anything else — {@code unreadOnly}, {@code sort}, a
 *                 session id — as strings, so it round-trips through any store
 */
public record ViewState(Set<String> selected, String query, int page, Map<String, String> extra) {

    public static final ViewState EMPTY = new ViewState(Set.of(), null, 1, Map.of());

    public ViewState {
        selected = selected == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(selected));
        query = query == null || query.isBlank() ? null : query.strip();
        page = Math.max(1, page);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    /** Exactly these rows ticked, no others. */
    public ViewState select(Collection<String> rowIds) {
        return new ViewState(rowIds == null ? Set.of() : new LinkedHashSet<>(rowIds), query, page, extra);
    }

    /** These rows ticked as well. */
    public ViewState tick(Collection<String> rowIds) {
        Set<String> all = new LinkedHashSet<>(selected);
        if (rowIds != null) all.addAll(rowIds);
        return new ViewState(all, query, page, extra);
    }

    /** These rows no longer ticked. */
    public ViewState untick(Collection<String> rowIds) {
        Set<String> left = new LinkedHashSet<>(selected);
        if (rowIds != null) left.removeAll(rowIds);
        return new ViewState(left, query, page, extra);
    }

    /** A new search starts on page one. */
    public ViewState search(String query) {
        return new ViewState(selected, query, 1, extra);
    }

    public ViewState page(int page) {
        return new ViewState(selected, query, page, extra);
    }

    public ViewState with(String key, String value) {
        Map<String, String> more = new LinkedHashMap<>(extra);
        if (value == null) more.remove(key); else more.put(key, value);
        return new ViewState(selected, query, page, more);
    }

    public String extra(String key) {
        return extra.get(key);
    }

    public boolean flag(String key) {
        return Boolean.parseBoolean(extra.get(key));
    }

    /**
     * The ticked rows after a move, under the names they have now. A moved
     * message gets a new id at IMAP and Graph, so a tick on the old row id
     * would tick nothing.
     */
    public ViewState renamed(Map<String, String> oldToNew) {
        if (oldToNew == null || oldToNew.isEmpty()) return this;
        Set<String> moved = new LinkedHashSet<>();
        for (String rowId : selected) moved.add(oldToNew.getOrDefault(rowId, rowId));
        return new ViewState(moved, query, page, extra);
    }
}
