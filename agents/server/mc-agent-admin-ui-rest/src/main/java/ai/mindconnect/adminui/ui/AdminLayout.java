package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiAppShell;
import ai.mindconnect.ui.model.UiHeader;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiMenu;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.ui.model.UiPage;

/**
 * Builds the shared admin-ui chrome as a semantic-ui {@link UiAppShell}:
 * a {@link UiHeader} (brand, burger, user widget) across the top, a
 * {@link UiMenu} side navigation with one entry per section, and the page
 * content filling the rest. The shell wires the header's burger to the menu
 * and handles collapse (expanded ⇄ icon rail on desktop, overlay drawer on
 * a narrow screen) — no layout CSS on our side.
 *
 * <p>A new instance is created per request with the current user, so the shell
 * reflects who is signed in and whether a logout link should be shown.
 */
public final class AdminLayout {

    private final String userName;
    private final boolean authEnabled;

    /**
     * @param userName    display name of the current user (e.g. {@code "mc_user"})
     * @param authEnabled whether Keycloak auth is on; the logout link is shown
     *                    only then (with auth off the user is a fixed dev user)
     */
    public AdminLayout(String userName, boolean authEnabled) {
        this.userName = userName;
        this.authEnabled = authEnabled;
    }

    /**
     * Wraps {@code page}'s content with the app shell. Only the node is
     * layouted — every other page-level field ({@code navigate}, toasts,
     * dialogs, active streams) must survive the pass untouched, or features
     * like the live-run stream resume silently break.
     */
    public UiPage withLayout(UiPage page) {
        UiAppShell shell = UiAppShell.of("admin-layout")
                .header(buildHeader())
                .menu(buildMenu(page.getNavigate()))
                .content(page.getNode());

        UiPage out = UiPage.of(page.getNavigate(), shell);
        out.setToasts(page.getToasts());
        out.setDialogs(page.getDialogs());
        out.setActiveStreams(page.getActiveStreams());
        return out;
    }

    private UiHeader buildHeader() {
        var header = UiHeader.of("Mindconnect Agent Runtime")
                .brandHref("/admin/agents")
                .brandLogo("/img/logo.svg");

        // Logout leaves the SPA (Spring Security + Keycloak RP-initiated
        // logout), so it's a plain link, not a semantic-ui action. Shown only
        // when auth is enabled — with auth off there's a fixed dev user and
        // nothing to log out of.
        if (authEnabled) {
            header.extra(UiLink.of("logout", "/admin/logout", "Logout"));
        }

        header.user(UiHeader.User.of(userName, initials(userName), null));
        return header;
    }

    private UiMenu buildMenu(String navigate) {
        UiMenu menu = UiMenu.of("app-menu", null);
        menu.mode(UiMenu.Mode.RESPONSIVE);
        menu.item(navItem("nav-agents", "Agents", "/admin/agents", "bot", navigate));
        menu.item(navItem("nav-tools", "Tools", "/admin/tools", "tools", navigate));
        menu.item(navItem("nav-llm-configs", "LLM Configs", "/admin/llm-configs", "ai", navigate));
        menu.item(navItem("nav-workflows", "Workflows", "/workflow-admin", "branch", navigate));
        menu.item(navItem("nav-vector-stores", "Vector Stores", "/admin/vector-stores", "database", navigate));
        menu.item(navItem("nav-migrations", "Migrations", "/admin/migrations", "refresh", navigate));
        menu.item(navItem("nav-api", "API", "/admin/api-explorer", "code", navigate));
        return menu;
    }

    /**
     * Builds a nav entry, marking it selected when the current page's
     * {@code navigate} path falls under the entry's section. Sessions live
     * under the Agents section, so a prefix match is what we want.
     */
    private static UiMenuItem navItem(String id, String label, String href, String icon, String navigate) {
        return UiMenuItem.link(id, label, href).icon(icon).selected(isActive(href, navigate));
    }

    private static boolean isActive(String href, String navigate) {
        if (navigate == null) return false;
        // Sessions are opened from an agent, so they belong to the Agents section.
        if (href.equals("/admin/agents") && navigate.startsWith("/admin/sessions")) {
            return true;
        }
        return navigate.startsWith(href);
    }

    /** 1–2 char avatar abbreviation from the user name. */
    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("[\\s_.-]+");
        if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }
}
