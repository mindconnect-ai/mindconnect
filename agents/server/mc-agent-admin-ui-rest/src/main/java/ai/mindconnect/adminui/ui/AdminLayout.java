package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiAppShell;
import ai.mindconnect.ui.model.UiHeader;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiMenu;
import ai.mindconnect.ui.model.UiMenuButton;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.ArrayList;
import java.util.List;

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
    private final String versionLabel;
    private final UiNode taskBadge;
    private final UiPage.ActiveStream taskStream;
    /** Whether this host has an MCP gateway to administer; false hides its entry. */
    private final boolean mcpGateway;
    /** Whether this host can browse registries — same idea as {@link #mcpGateway}. */
    private final boolean registry;
    /** The namespaces the user may work in and the one they are in; null when the host has no namespaces. */
    private NamespaceSwitch namespaces;

    /**
     * What the header's namespace switcher shows: the active namespace and
     * every one the user may switch to, in the order the service lists them.
     */
    public record NamespaceSwitch(String activeId, String activeLabel, List<Entry> entries) {
        public record Entry(String id, String label) {}
    }

    /**
     * @param userName    display name of the current user (e.g. {@code "mc_user"})
     * @param authEnabled whether Keycloak auth is on; the logout link is shown
     *                    only then (with auth off the user is a fixed dev user)
     * @param versionLabel the build's short version for the sidebar's foot, or
     *                     null when this is not a packaged build
     */
    public AdminLayout(String userName, boolean authEnabled, String versionLabel) {
        this(userName, authEnabled, versionLabel, null, null, false);
    }

    /**
     * @param taskBadge  the task-queue badge for the header, or null when the
     *                   host has no task monitor
     * @param taskStream the user's live feed — the badge's patches and the
     *                   session events; put on every page so the SPA attaches
     *                   once and keeps it across navigation
     */
    public AdminLayout(String userName, boolean authEnabled, String versionLabel,
                       UiNode taskBadge, UiPage.ActiveStream taskStream) {
        this(userName, authEnabled, versionLabel, taskBadge, taskStream, false);
    }

    /**
     * @param mcpGateway whether this host can administer MCP servers. The
     *                   screen is contributed by a module and conditional on
     *                   a gateway bean, so the entry has to be conditional
     *                   on the same thing — a nav item to a route nobody
     *                   serves is a 404 with a label.
     */
    public AdminLayout(String userName, boolean authEnabled, String versionLabel,
                       UiNode taskBadge, UiPage.ActiveStream taskStream, boolean mcpGateway) {
        this(userName, authEnabled, versionLabel, taskBadge, taskStream, mcpGateway, false);
    }

    /**
     * @param registry whether this host can browse registries. Conditional for
     *                 the same reason as {@code mcpGateway}: the screen is
     *                 contributed by a module, and a nav item to a route
     *                 nobody serves is a 404 with a label.
     */
    public AdminLayout(String userName, boolean authEnabled, String versionLabel,
                       UiNode taskBadge, UiPage.ActiveStream taskStream, boolean mcpGateway,
                       boolean registry) {
        this.userName = userName;
        this.authEnabled = authEnabled;
        this.versionLabel = versionLabel;
        this.taskBadge = taskBadge;
        this.taskStream = taskStream;
        this.mcpGateway = mcpGateway;
        this.registry = registry;
    }

    /** Adds the namespace switcher to the header; without it the shell shows no namespace at all. */
    public AdminLayout namespaces(NamespaceSwitch namespaces) {
        this.namespaces = namespaces;
        return this;
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
        out.setActiveStreams(withTaskStream(page.getActiveStreams()));
        return out;
    }

    /**
     * The page's own streams (a chat session's, say) plus the task feed. The
     * client opens a stream only when it has none under that channel id, so
     * naming it on every page costs nothing after the first.
     */
    private List<UiPage.ActiveStream> withTaskStream(List<UiPage.ActiveStream> streams) {
        if (taskStream == null) return streams;
        List<UiPage.ActiveStream> out = streams == null ? new ArrayList<>() : new ArrayList<>(streams);
        boolean present = out.stream().anyMatch(s -> taskStream.getChannelId().equals(s.getChannelId()));
        if (!present) out.add(taskStream);
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
        // The task badge is the one live thing in the header: what the server
        // is DOING right now, beside who is using it. A click opens the task
        // manager. What the server IS — its version — lives at the foot of
        // the sidebar (see buildMenu), where it is out of the way.
        if (taskStream != null) {
            // The element the SPA looks for to keep the user's stream
            // attached across navigation: its id is the stream's channel
            // id, and it is here on every page, badge or no badge.
            UiStack live = UiStack.of(taskStream.getChannelId())
                    .direction(UiStack.Direction.HORIZONTAL).gap(0);
            live.withCssClass("user-stream-anchor");
            if (taskBadge != null) live.child(taskBadge);
            header.extra(live);
        } else if (taskBadge != null) {
            header.extra(taskBadge);
        }
        // Where the user works: the active namespace as a menu button, every
        // namespace they may switch to below it. Switching is a GET the SPA
        // follows through a redirect to the agents list, so the whole shell —
        // this button included — is rendered afresh in the new namespace.
        if (namespaces != null) {
            UiMenuButton switcher = UiMenuButton.of("namespace-switch")
                    .label(namespaces.activeLabel()).icon("layers")
                    .variant(UiMenuButton.Variant.BUTTON).align(UiMenuButton.Align.END);
            for (NamespaceSwitch.Entry entry : namespaces.entries()) {
                boolean active = entry.id().equals(namespaces.activeId());
                switcher.item(UiMenuItem.of("namespace-switch-" + entry.id(), entry.label())
                        .icon(active ? "check" : "layers").selected(active)
                        .onClick(UiTrigger.api("POST", "/admin/api/namespaces/switch/" + entry.id())));
            }
            switcher.item(UiMenuItem.divider());
            switcher.item(UiMenuItem.of("namespace-new", "New namespace…").icon("add")
                    .onClick(UiTrigger.api("GET", "/admin/api/namespaces/new")));
            switcher.item(UiMenuItem.link("namespace-manage", "Namespaces & members", "/admin/namespaces").icon("users"));
            header.extra(switcher);
        }
        if (authEnabled) {
            header.extra(UiLink.of("logout", "/admin/logout", "Logout"));
        }

        // The avatar leads to the user's own page: who they are signed in as,
        // and the API tokens they issued.
        header.user(UiHeader.User.of(userName, initials(userName), "/admin/profile"));
        return header;
    }

    private UiMenu buildMenu(String navigate) {
        UiMenu menu = UiMenu.of("app-menu", null);
        menu.mode(UiMenu.Mode.RESPONSIVE);
        menu.item(navItem("nav-chat", "Chat", "/chat", "chat", navigate));
        menu.item(navItem("nav-agents", "Agents", "/admin/agents", "bot", navigate));
        menu.item(navItem("nav-tools", "Tools", "/admin/tools", "tools", navigate));
        menu.item(navItem("nav-skills", "Skills", "/admin/skills", "graduation-cap", navigate));
        menu.item(navItem("nav-llm-configs", "LLM Configs", "/admin/llm-configs", "ai", navigate));
        menu.item(navItem("nav-workflows", "Workflows", "/workflow-admin", "branch", navigate));
        if (mcpGateway) {
            menu.item(navItem("nav-mcp", "MCP Servers", "/mcp-gateway", "plug", navigate));
        }
        menu.item(navItem("nav-vector-stores", "Vector Stores", "/admin/vector-stores", "database", navigate));
        menu.item(installGroup(navigate));
        menu.item(navItem("nav-api", "API", "/admin/api-explorer", "code", navigate));
        // The build's version as the last entry, pushed to the bottom by the
        // stylesheet: small and muted, an info icon in the collapsed rail. A
        // click opens the About dialog with build time, commit, branch and
        // the changelog section of this build.
        if (versionLabel != null) {
            menu.item(UiMenuItem.of("nav-version", versionLabel).icon("info")
                    .onClick(UiTrigger.api("GET", "/admin/api/about")));
        }
        return menu;
    }

    /**
     * The two ways something arrives in this installation, under one entry:
     * from a registry, or from the application's own seed data. Open while one
     * of them is the current page, so the selected item is not hidden in a
     * closed group.
     */
    private UiMenuItem installGroup(String navigate) {
        UiMenuItem install = UiMenuItem.group("nav-install", "Install").icon("download");
        if (registry) {
            install.child(navItem("nav-registry", "Registry", "/registry", "package", navigate));
        }
        install.child(navItem("nav-migrations", "Migrations", "/admin/migrations", "refresh", navigate));
        return install.open(install.getChildren().stream().anyMatch(UiMenuItem::isSelected));
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
