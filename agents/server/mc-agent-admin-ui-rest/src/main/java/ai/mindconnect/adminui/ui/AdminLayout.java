package ai.mindconnect.adminui.ui;

import ai.mindconnect.adminui.branding.BrandingProperties;
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
    /** The notification bell, or null on a host that keeps no notifications. */
    private UiNode notificationBell;
    private final UiPage.ActiveStream taskStream;
    /** Whether this host has an MCP gateway to administer; false hides its entry. */
    private final boolean mcpGateway;
    /** Whether this host can browse registries — same idea as {@link #mcpGateway}. */
    private final boolean registry;

    /**
     * Whether the navigation is the chat and nothing else: what somebody sees
     * who is a user of the namespace they are in rather than one of its admins
     * (see {@code NamespaceRole}). Set per render, because it follows the
     * namespace they are working in right now, not the account.
     */
    private boolean chatOnly;
    /** The namespaces the user may work in and the one they are in; null when the host has no namespaces. */
    private NamespaceSwitch namespaces;
    /** What the header calls this installation; the shipped one until {@link #brand} says otherwise. */
    private Brand brand = Brand.DEFAULT;
    /** Entries modules add to the sidebar, already filtered for this viewer; see {@link AdminMenuContribution}. */
    private List<AdminMenuContribution.Entry> contributed = List.of();

    /**
     * The installation's name and mark in the header: the heading, the logo
     * beside it (null for none) and where a click on either leads. Comes from
     * {@code mindconnect.branding} — see
     * {@link ai.mindconnect.adminui.branding.BrandingProperties}.
     */
    public record Brand(String title, String logo, String href) {
        /** What the header showed before any of this was configurable. */
        public static final Brand DEFAULT = new Brand(BrandingProperties.DEFAULT_TITLE,
                BrandingProperties.DEFAULT_LOGO, BrandingProperties.DEFAULT_LOGO_HREF);
    }

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

    /** Names the installation in the header — heading, logo and where the brand leads. */
    public AdminLayout brand(Brand brand) {
        this.brand = brand == null ? Brand.DEFAULT : brand;
        return this;
    }

    /**
     * Adds the notification bell to the header; without it there is none.
     * Set per request by {@code AdminLayoutFactory}, and only where the host
     * keeps notifications at all — a bell that can never have anything in it
     * is one more thing to look at for nothing.
     */
    public AdminLayout notifications(UiNode bell) {
        this.notificationBell = bell;
        return this;
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
        var header = UiHeader.of(brand.title()).brandHref(brand.href())
                // One row whatever the width: what does not fit — the task chip,
                // the bell, the namespace switch, the theme switch js/theme-switch.js
                // puts among them — goes into a "⋯" menu rather than squeezing
                // the brand to nothing on a phone.
                .extrasOverflow(UiHeader.ExtrasOverflow.MENU);
        // No logo is a valid answer: an installation whose mark is the word
        // itself wants the heading alone, not the shipped one as a fallback.
        if (brand.logo() != null) {
            header.brandLogo(brand.logo());
        }

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
        // What is waiting for this user, beside what the server is doing: the
        // bell sits between the two, in front of the namespace switcher, so
        // the header reads server → you → where you are.
        if (notificationBell != null) {
            header.extra(notificationBell);
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

    /** Renders the chat alone — for a user of this namespace, who shapes nothing in it. */
    public AdminLayout chatOnly(boolean chatOnly) {
        this.chatOnly = chatOnly;
        return this;
    }

    /**
     * The entries modules add to the sidebar, in order. Already the ones for
     * this viewer: the caller asked each {@link AdminMenuContribution} with
     * whether the viewer is an admin, so a chat-only viewer gets exactly what
     * a contribution said is open to them.
     */
    public AdminLayout contributions(List<AdminMenuContribution.Entry> entries) {
        this.contributed = entries == null ? List.of() : List.copyOf(entries);
        return this;
    }

    private UiMenu buildMenu(String navigate) {
        UiMenu menu = UiMenu.of("app-menu", null);
        menu.mode(UiMenu.Mode.RESPONSIVE);
        menu.item(navItem("nav-chat", "Chat", "/chat", "chat", navigate));
        if (chatOnly) {
            // A user of this namespace, not one of its admins: the chat is what
            // they came for, and an entry they may not open is worse than none.
            // The server refuses those routes as well — this is the friendly
            // half of that, not the guard (see NamespaceAccessInterceptor).
            contributed(menu, navigate);
            version(menu);
            return menu;
        }
        menu.item(aiGroup(navigate));
        menu.item(toolsGroup(navigate));
        menu.item(dataGroup(navigate));
        contributed(menu, navigate);
        menu.item(installGroup(navigate));
        menu.item(navItem("nav-api", "API", "/admin/api-explorer", "code", navigate));
        version(menu);
        return menu;
    }

    /**
     * The entries modules contributed, after the shipped sections and before
     * the Install group. Groups with one id, from however many contributions,
     * become one group with all their links, in the order they came — and a
     * contribution to a group the menu already has, {@code nav-group-data}
     * say, joins that group instead of standing beside it. A group is open
     * while one of its links is the current page, like Install.
     */
    private void contributed(UiMenu menu, String navigate) {
        java.util.Map<String, UiMenuItem> groups = new java.util.LinkedHashMap<>();
        for (UiMenuItem shipped : menu.getItems()) {
            if (shipped.getChildren() != null && !shipped.getChildren().isEmpty()) groups.put(shipped.getId(), shipped);
        }
        java.util.Set<String> touched = new java.util.HashSet<>();
        for (AdminMenuContribution.Entry entry : contributed) {
            if (!entry.isGroup()) {
                menu.item(navItem(entry.id(), entry.label(), entry.href(), entry.icon(), navigate));
                continue;
            }
            UiMenuItem group = groups.get(entry.id());
            if (group == null) {
                group = UiMenuItem.group(entry.id(), entry.label());
                if (entry.icon() != null) group.icon(entry.icon());
                groups.put(entry.id(), group);
                menu.item(group);
            }
            touched.add(entry.id());
            for (AdminMenuContribution.Entry child : entry.children()) {
                group.child(navItem(child.id(), child.label(), child.href(), child.icon(), navigate));
            }
        }
        for (String id : touched) {
            UiMenuItem group = groups.get(id);
            group.open(group.getChildren().stream().anyMatch(UiMenuItem::isSelected));
        }
    }

    /**
     * The build's version as the last entry, pushed to the bottom by the
     * stylesheet: small and muted, an info icon in the collapsed rail. A click
     * opens the About dialog with build time, commit, branch and the changelog
     * section of this build. Everybody sees it — which build is running is not
     * an administrator's secret.
     */
    private void version(UiMenu menu) {
        if (versionLabel != null) {
            menu.item(UiMenuItem.of("nav-version", versionLabel).icon("info")
                    .onClick(UiTrigger.api("GET", "/admin/api/about")));
        }
    }

    /**
     * What thinks: the agents, the models they run on and the skills they are
     * given. Open while one of them is the current page, like Install.
     */
    private UiMenuItem aiGroup(String navigate) {
        UiMenuItem ai = UiMenuItem.group("nav-group-ai", "AI").icon("bot");
        ai.child(navItem("nav-agents", "Agents", "/admin/agents", "bot", navigate));
        ai.child(navItem("nav-llm-configs", "LLM Configs", "/admin/llm-configs", "ai", navigate));
        ai.child(navItem("nav-skills", "Skills", "/admin/skills", "graduation-cap", navigate));
        return openWhenSelected(ai);
    }

    /**
     * What an agent can call: tools, MCP servers and workflows. The MCP entry
     * follows the gateway bean — without one, nobody serves that route.
     */
    private UiMenuItem toolsGroup(String navigate) {
        UiMenuItem tools = UiMenuItem.group("nav-group-tools", "Tools").icon("tools");
        tools.child(navItem("nav-tools", "Tools", "/admin/tools", "tools", navigate));
        if (mcpGateway) {
            tools.child(navItem("nav-mcp", "MCP Servers", "/mcp-gateway", "plug", navigate));
        }
        tools.child(navItem("nav-workflows", "Workflows", "/workflow-admin", "branch", navigate));
        return openWhenSelected(tools);
    }

    /**
     * What an agent reads: for now the vector stores. Its own group, one entry
     * or not, so documents and sources have a home when they arrive.
     */
    private UiMenuItem dataGroup(String navigate) {
        UiMenuItem data = UiMenuItem.group("nav-group-data", "Data").icon("database");
        data.child(navItem("nav-vector-stores", "Vector Stores", "/admin/vector-stores", "database", navigate));
        return openWhenSelected(data);
    }

    /** A group is open while one of its links is the current page, so the selected item is never hidden. */
    private static UiMenuItem openWhenSelected(UiMenuItem group) {
        return group.open(group.getChildren().stream().anyMatch(UiMenuItem::isSelected));
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
        install.child(navItem("nav-extensions", "Extensions", "/admin/extensions", "package", navigate));
        return openWhenSelected(install);
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
