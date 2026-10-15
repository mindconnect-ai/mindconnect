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
 * <p>An entry is a link, or a group of links — the shape of the shipped
 * Install group. Groups with the same id from different contributions
 * become one group, so two modules can both file their screens under
 * "Reports" without knowing of each other.
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
     * {@code navigate} path falls under its {@code href}; or, with
     * {@code children}, a group of links that opens while one of them is
     * current.
     *
     * @param id       the menu item's id, unique across the menu — by
     *                 convention {@code nav-<section>}
     * @param label    what the entry says
     * @param href     where a link leads, and its section prefix for "is
     *                 current"; null for a group
     * @param icon     an icon name the shell knows, or null for none
     * @param children the links of a group, empty for a link
     */
    record Entry(String id, String label, String href, String icon, List<Entry> children) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            children = children == null ? List.of() : List.copyOf(children);
            if (href == null && children.isEmpty()) {
                throw new IllegalArgumentException("Entry " + id + " needs an href or children");
            }
        }

        /** A link. */
        public static Entry of(String id, String label, String href, String icon) {
            return new Entry(id, label, Objects.requireNonNull(href, "href"), icon, List.of());
        }

        /** A group of links, like the shipped Install group. */
        public static Entry group(String id, String label, String icon, List<Entry> children) {
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("Group " + id + " needs at least one child");
            }
            return new Entry(id, label, null, icon, children);
        }

        public boolean isGroup() {
            return !children.isEmpty();
        }
    }
}
