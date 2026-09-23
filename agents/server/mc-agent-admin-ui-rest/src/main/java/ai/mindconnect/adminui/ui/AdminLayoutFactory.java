package ai.mindconnect.adminui.ui;

import ai.mindconnect.adminui.branding.Branding;
import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.adminui.service.TaskMonitor;
import ai.mindconnect.adminui.service.UserStream;
import ai.mindconnect.adminui.ui.component.TaskMonitorComponent;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.starter.namespace.HostNamespaces;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.adminui.ui.component.NotificationsComponent;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.user.service.NotificationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Builds a per-request {@link AdminLayout} from the current security context.
 *
 * <p>Controllers call {@link #current()} and wrap their page:
 * {@code layoutFactory.current().withLayout(page)}. This keeps the header
 * (brand, nav, user widget, namespace switcher, optional logout) consistent
 * across every page without threading the {@code OidcUser} through page
 * constructors.
 */
@Component
public class AdminLayoutFactory {

    private final boolean authEnabled;
    private final BuildInfo buildInfo;
    /** Absent when the host runs no task queue; the header then has no badge. */
    private final TaskMonitor taskMonitor;
    /**
     * Present when this host has an MCP gateway to administer. Same shape as
     * the task monitor above: what the shell offers follows from what the
     * host actually assembled, and the composition point is the one place
     * that can see it.
     */
    private final ObjectProvider<McpRegistryAdmin> mcpRegistryAdmin;
    /** Present when this host has registries to browse — same shape as the two above. */
    private final ObjectProvider<RegistryService> registryService;
    /** Present when this host has namespaces — then the header carries the switcher. */
    private final ObjectProvider<NamespaceService> namespaceService;
    /** Where the request works — bound by the scope filter; the switcher marks it as active. */
    private final ObjectProvider<ScopeSupplier> scope;
    /**
     * What this installation calls itself. Read per request rather than held
     * as a value: with {@code mindconnect.branding.switch} the answer depends
     * on the host in the address bar, and one process can serve two brands.
     */
    private final BrandingProperties branding;
    /** Which host stands for which namespace; absent on a host that does not bind any. */
    private final ObjectProvider<HostNamespaces> hostNamespaces;
    /** Present when this host keeps notifications — then the header carries the bell. */
    private final ObjectProvider<NotificationService> notifications;
    /** The sidebar entries modules bring along; see {@link AdminMenuContribution}. */
    private final ObjectProvider<AdminMenuContribution> contributions;
    /**
     * Present when this host knows its extensions — then an entry a switched-off
     * extension declared under {@code contributes.ui.menu} is left out for the
     * namespace that switched it off.
     */
    private final ObjectProvider<ExtensionService> extensions;

    @Autowired
    public AdminLayoutFactory(@Value("${mindconnect.auth.enabled:false}") boolean authEnabled,
                              BuildInfo buildInfo,
                              Optional<TaskMonitor> taskMonitor,
                              ObjectProvider<McpRegistryAdmin> mcpRegistryAdmin,
                              ObjectProvider<RegistryService> registryService,
                              ObjectProvider<NamespaceService> namespaceService,
                              ObjectProvider<ScopeSupplier> scope,
                              BrandingProperties branding,
                              ObjectProvider<HostNamespaces> hostNamespaces,
                              ObjectProvider<NotificationService> notifications,
                              ObjectProvider<AdminMenuContribution> contributions,
                              ObjectProvider<ExtensionService> extensions) {
        this.contributions = contributions;
        this.extensions = extensions;
        this.notifications = notifications;
        this.branding = branding;
        this.hostNamespaces = hostNamespaces;
        this.authEnabled = authEnabled;
        this.buildInfo = buildInfo;
        this.taskMonitor = taskMonitor.orElse(null);
        this.mcpRegistryAdmin = mcpRegistryAdmin;
        this.registryService = registryService;
        this.namespaceService = namespaceService;
        this.scope = scope;
    }

    /** A host without namespaces and without branding: no switcher, the shipped name in the header. */
    public AdminLayoutFactory(boolean authEnabled,
                              BuildInfo buildInfo,
                              Optional<TaskMonitor> taskMonitor,
                              ObjectProvider<McpRegistryAdmin> mcpRegistryAdmin,
                              ObjectProvider<RegistryService> registryService) {
        this(authEnabled, buildInfo, taskMonitor, mcpRegistryAdmin, registryService, none(), none(),
                new BrandingProperties(), none(), none(), none(), none());
    }

    /**
     * Layout for the user currently in the {@link SecurityContextHolder}.
     * Every page names the user's stream, badge or no badge: the stream
     * carries the session events even where there is no task queue to show.
     */
    public AdminLayout current() {
        AdminLayout layout = new AdminLayout(currentUserName(), authEnabled, buildInfo.label(),
                taskMonitor == null ? null : TaskMonitorComponent.badge(taskMonitor.counts(currentNamespace())),
                UiPage.ActiveStream.of(UserStream.CHANNEL_ID, UserStream.STREAM_URL,
                        "Live updates", "/admin/agents"),
                mcpRegistryAdmin.getIfAvailable() != null,
                registryService.getIfAvailable() != null);
        layout.brand(currentBrand());
        boolean admin = shapesCurrentNamespace();
        layout.chatOnly(!admin);
        ExtensionService known = extensions.getIfAvailable();
        layout.contributions(visible(distinct(contributions.orderedStream()
                .flatMap(contribution -> contribution.entries(admin).stream())
                .toList()), known == null ? id -> false : known::hidesMenuEntry));
        notificationBell().ifPresent(layout::notifications);
        namespaceSwitch().ifPresent(layout::namespaces);
        return layout;
    }

    /**
     * The contributed entries with every id once: a module's own bean comes
     * first (contributions are ordered, the manifest's last), so an entry a
     * manifest describes <em>and</em> a bean registers is the bean's. A group
     * keeps its first occurrence's label; later occurrences only add members.
     */
    static List<AdminMenuContribution.Entry> distinct(List<AdminMenuContribution.Entry> entries) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Map<String, List<AdminMenuContribution.Entry>> groupMembers = new java.util.LinkedHashMap<>();
        java.util.Map<String, AdminMenuContribution.Entry> groups = new java.util.LinkedHashMap<>();
        List<Object> order = new ArrayList<>();   // links, and group ids, in first-seen order
        for (AdminMenuContribution.Entry entry : entries) {
            if (!entry.isGroup()) {
                if (seen.add(entry.id())) order.add(entry);
                continue;
            }
            if (!groups.containsKey(entry.id())) {
                groups.put(entry.id(), entry);
                order.add(entry.id());
            }
            List<AdminMenuContribution.Entry> members = groupMembers.computeIfAbsent(entry.id(), id -> new ArrayList<>());
            for (AdminMenuContribution.Entry child : entry.children()) {
                if (seen.add(child.id())) members.add(child);
            }
        }
        List<AdminMenuContribution.Entry> kept = new ArrayList<>();
        for (Object item : order) {
            if (item instanceof AdminMenuContribution.Entry link) {
                kept.add(link);
            } else {
                AdminMenuContribution.Entry group = groups.get((String) item);
                List<AdminMenuContribution.Entry> members = groupMembers.get(group.id());
                if (!members.isEmpty()) {
                    kept.add(new AdminMenuContribution.Entry(group.id(), group.label(), null, group.icon(), members));
                }
            }
        }
        return kept;
    }

    /**
     * The contributed entries without those a switched-off extension declared:
     * a link whose id is hidden goes, a group loses such children and goes
     * itself once it has none left.
     */
    static List<AdminMenuContribution.Entry> visible(List<AdminMenuContribution.Entry> entries, Predicate<String> hidden) {
        List<AdminMenuContribution.Entry> kept = new ArrayList<>();
        for (AdminMenuContribution.Entry entry : entries) {
            if (hidden.test(entry.id())) continue;
            if (!entry.isGroup()) {
                kept.add(entry);
                continue;
            }
            List<AdminMenuContribution.Entry> children = entry.children().stream()
                    .filter(child -> !hidden.test(child.id())).toList();
            if (!children.isEmpty()) {
                kept.add(new AdminMenuContribution.Entry(entry.id(), entry.label(), null, entry.icon(), children));
            }
        }
        return kept;
    }

    /**
     * The bell as it stands for the signed-in user: absent on a host that
     * keeps no notifications, and absent off a request that carries a user —
     * a scheduled render has nobody to have notices.
     */
    private Optional<UiNode> notificationBell() {
        NotificationService service = notifications.getIfAvailable();
        if (service == null) return Optional.empty();
        return currentUserId().map(user ->
                NotificationsComponent.badge(service.unreadCount(user), service.hasActionRequired(user)));
    }

    /**
     * Whether the signed-in user is an admin of the namespace this request works
     * in. Without a namespace service — a host that embeds the UI without
     * namespaces — everybody shapes what they see, which is how it was before
     * roles existed.
     */
    private boolean shapesCurrentNamespace() {
        NamespaceService namespaces = namespaceService.getIfAvailable();
        ScopeSupplier current = scope.getIfAvailable();
        if (namespaces == null || current == null) return true;
        return currentUserId().map(user -> namespaces.isAdmin(user, current.namespace())).orElse(true);
    }

    /** The switcher's content: the namespaces the current user may work in, and the one the request is in. */
    Optional<AdminLayout.NamespaceSwitch> namespaceSwitch() {
        NamespaceService namespaces = namespaceService.getIfAvailable();
        ScopeSupplier current = scope.getIfAvailable();
        if (namespaces == null || current == null) return Optional.empty();
        UserId user = currentUserId().orElse(null);
        if (user == null) return Optional.empty();
        Namespace active = current.namespace();
        // What this address serves: the namespaces of its brand — the brand's
        // own and the ones its people made. A menu of alternatives the page
        // would refuse is worse than no menu.
        List<NamespaceDefinition> mine = namespaces.forUser(user, hostNamespace().orElse(null));
        String activeLabel = mine.stream().filter(ns -> ns.id().equals(active)).map(NamespaceDefinition::label)
                .findFirst().orElse(active.value());
        return Optional.of(new AdminLayout.NamespaceSwitch(active.value(), activeLabel,
                mine.stream().map(ns -> new AdminLayout.NamespaceSwitch.Entry(ns.id().value(), ns.label())).toList()));
    }

    /** The namespace the host of this request stands for, if it stands for one. */
    private Optional<Namespace> hostNamespace() {
        HostNamespaces byHost = hostNamespaces.getIfAvailable();
        return byHost == null ? Optional.empty() : byHost.namespaceOf(currentHost());
    }

    /** The brand for the host this request came in on; the top-level settings when it matches none. */
    private AdminLayout.Brand currentBrand() {
        Branding resolved = branding.resolve(currentHost());
        return new AdminLayout.Brand(resolved.title(), resolved.logo(), resolved.logoHref());
    }

    /** The host in the address bar, or null off a request thread — a scheduled render, a test. */
    private static String currentHost() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getServerName() : null;
    }

    /** The namespace the request works in; null (everything) for a host without namespaces. */
    private Namespace currentNamespace() {
        ScopeSupplier current = scope.getIfAvailable();
        return current == null ? null : current.namespace();
    }

    private String currentUserName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OidcUser user) {
            String name = user.getPreferredUsername();
            if (name == null || name.isBlank()) name = user.getFullName();
            if (name != null && !name.isBlank()) return name;
        }
        return "user";
    }

    private static Optional<UserId> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OidcUser user
                && user.getPreferredUsername() != null && !user.getPreferredUsername().isBlank()) {
            return Optional.of(UserId.of(user.getPreferredUsername()));
        }
        return Optional.empty();
    }

    private static <T> ObjectProvider<T> none() {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return null; }
            @Override public java.util.stream.Stream<T> stream() { return java.util.stream.Stream.empty(); }
            @Override public java.util.stream.Stream<T> orderedStream() { return java.util.stream.Stream.empty(); }
        };
    }
}
