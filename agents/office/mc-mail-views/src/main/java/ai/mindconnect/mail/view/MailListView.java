package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;

import java.util.Collection;
import java.util.Map;

/**
 * A list of mail on the screen, as a thing with a name — the inbox, all
 * inboxes, what an agent gathered, a saved search.
 *
 * <p>One interface rather than one class with a mode, because the modes kept
 * multiplying inside the one class and every button had to know them all.
 * Here the differences live in the implementations: a folder reads its rows
 * from the provider and cannot lose one, a gathered list reads them from
 * wherever they lie and can. The screen and the agent see only this.
 *
 * <p>A view carries its {@link ViewState} — selection, search, page — and is
 * saved with it, so what a person did to it is still so when they come back
 * or when an agent asks.
 */
public interface MailListView {

    /** The name a URL carries and the store files it under. */
    ViewId id();

    /**
     * What kind of view this is — {@code folder}, {@code all-inboxes},
     * {@code agent}, … A string, not an enum: a new kind is a new class and a
     * new word, and touches nothing here.
     */
    String kind();

    UserId owner();

    /** What the header says: the folder's name, or what the agent called it. */
    String title();

    /** One page of rows. The one place kinds differ in how they read. */
    ListPage page(int offset, int limit);

    /**
     * Where a row lies — so deleting and moving know which mailbox and folder
     * to ask. Answered by the view from what it knows of its rows, never by
     * taking a row id apart.
     */
    Location location(String rowId);

    ViewState state();

    MailListView withState(ViewState state);

    /**
     * What the store keeps of this view beside its state — a gathered list's
     * entries, say. Empty for a view that can be rebuilt from its id.
     */
    Map<String, String> data();

    /** True when the rows come from somewhere that changes on its own: the search box and the pager make sense. */
    boolean lives();

    /** True when a row can be taken out of the view without touching the message. */
    boolean canRemove();

    /** True when rows here can be deleted and moved at all — false over POP3, which has one folder and no wastebasket. */
    default boolean organises() {
        return true;
    }

    /** The view without these rows; the view itself when it cannot remove. */
    MailListView without(Collection<String> rowIds);

    /**
     * The view after some of its rows were moved. A folder loses them — they
     * are not in this folder any more. A gathered list keeps them, under
     * their new location and, at IMAP and Graph, their new id.
     *
     * @param moved old row id → where the message is now
     */
    default MailListView afterMove(Map<String, MailMessage.Ref> moved) {
        return without(moved.keySet());
    }

    /**
     * The one account this view is about, or null when it spans several or
     * none — what the sidebar opens, what the composer writes from.
     */
    default String account() {
        return null;
    }

    /** The one folder this view is about, or null — what the sidebar highlights. */
    default String folderId() {
        return null;
    }

    /** Where "back" leads from this view, or null when there is no back. */
    default ViewId from() {
        return null;
    }

    /** {@link ViewState#extra} key: the chat this view belongs to. */
    String SESSION = "sessionId";

    /**
     * The chat this view belongs to — the one that gathered it — or null.
     * Opening the view opens that chat again beside it: a list and the
     * conversation that made it are one thing to the person.
     */
    default String session() {
        return state().extra(SESSION);
    }

    /** True for a view that has no record in the store until somebody changes its state. */
    default boolean derived() {
        return data().isEmpty() && !id().isSaved();
    }
}
