package ai.mindconnect.adminui.ui;

import java.util.List;
import java.util.Objects;

/**
 * A screen a module brings to the admin UI: its entry in the sidebar.
 *
 * <p>The navigation is built per request from what the host knows — the
 * shipped sections, whether there is an MCP gateway, a registry. A module on
 * the classpath that serves a screen of its own has no say in that list, so
 * its page is reachable by URL only. This is how it gets an entry: every bean
 * of this type is asked once per page, and its entries are appended after
 * the shipped sections, before the Install group.
 *
 * <p>A contribution answers for the viewer at hand: {@code admin} says
 * whether they administer the namespace they are in. Somebody who is a
 * plain user of it sees the chat and nothing else, because the server
 * refuses the admin routes — an entry they may not open is worse than none,
 * so a contribution offers such a viewer only what is open to them, or
 * nothing.
 */
public interface AdminMenuContribution {

    /**
     * The entries to add for this viewer, in the order they appear.
     *
     * @param admin whether the viewer administers the current namespace
     */
    List<Entry> entries(boolean admin);

    /**
     * One sidebar entry: a link, marked as the current one while the page's
     * {@code navigate} path falls under its {@code href}.
     *
     * @param id    the menu item's id, unique across the menu — by convention
     *              {@code nav-<section>}
     * @param label what the entry says
     * @param href  where it leads; also the section prefix for "is current"
     * @param icon  an icon name the shell knows, or null for none
     */
    record Entry(String id, String label, String href, String icon) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(href, "href");
        }

        public static Entry of(String id, String label, String href, String icon) {
            return new Entry(id, label, href, icon);
        }
    }
}
