package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;

/**
 * Builds the views of one kind — the seam a new kind of view plugs into.
 *
 * <p>A view is opened in one of two ways. Either its id says what it is — a
 * folder's, all inboxes' — and the factory builds it from the id and
 * whatever state was stored for it, or nothing was. Or it exists only in
 * the store, under a name that says nothing, and the stored record's
 * {@code kind} names the factory. Both go through {@link #open}.
 */
public interface MailListViewFactory {

    /** The kind this builds — what {@link MailListView#kind()} answers and the store files under. */
    String kind();

    /** True when this factory can build a view from that id alone, with no record of it. */
    boolean derives(ViewId id);

    /**
     * The view.
     *
     * @param stored what the store had, or a fresh record for a view that has none yet
     */
    MailListView open(UserId user, ViewId id, StoredView stored);
}
