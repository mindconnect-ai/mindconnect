package ai.mindconnect.adminui.ui;

import ai.mindconnect.adminui.service.TaskMonitor;
import ai.mindconnect.adminui.service.UserStream;
import ai.mindconnect.adminui.ui.component.TaskMonitorComponent;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

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

    @Autowired
    public AdminLayoutFactory(@Value("${mindconnect.auth.enabled:false}") boolean authEnabled,
                              BuildInfo buildInfo,
                              Optional<TaskMonitor> taskMonitor,
                              ObjectProvider<McpRegistryAdmin> mcpRegistryAdmin,
                              ObjectProvider<RegistryService> registryService,
                              ObjectProvider<NamespaceService> namespaceService,
                              ObjectProvider<ScopeSupplier> scope) {
        this.authEnabled = authEnabled;
        this.buildInfo = buildInfo;
        this.taskMonitor = taskMonitor.orElse(null);
        this.mcpRegistryAdmin = mcpRegistryAdmin;
        this.registryService = registryService;
        this.namespaceService = namespaceService;
        this.scope = scope;
    }

    /** A host without namespaces: no switcher in the header. */
    public AdminLayoutFactory(boolean authEnabled,
                              BuildInfo buildInfo,
                              Optional<TaskMonitor> taskMonitor,
                              ObjectProvider<McpRegistryAdmin> mcpRegistryAdmin,
                              ObjectProvider<RegistryService> registryService) {
        this(authEnabled, buildInfo, taskMonitor, mcpRegistryAdmin, registryService, none(), none());
    }

    /**
     * Layout for the user currently in the {@link SecurityContextHolder}.
     * Every page names the user's stream, badge or no badge: the stream
     * carries the session events even where there is no task queue to show.
     */
    public AdminLayout current() {
        AdminLayout layout = new AdminLayout(currentUserName(), authEnabled, buildInfo.label(),
                taskMonitor == null ? null : TaskMonitorComponent.badge(taskMonitor.counts()),
                UiPage.ActiveStream.of(UserStream.CHANNEL_ID, UserStream.STREAM_URL,
                        "Live updates", "/admin/agents"),
                mcpRegistryAdmin.getIfAvailable() != null,
                registryService.getIfAvailable() != null);
        namespaceSwitch().ifPresent(layout::namespaces);
        return layout;
    }

    /** The switcher's content: the namespaces the current user may work in, and the one the request is in. */
    Optional<AdminLayout.NamespaceSwitch> namespaceSwitch() {
        NamespaceService namespaces = namespaceService.getIfAvailable();
        ScopeSupplier current = scope.getIfAvailable();
        if (namespaces == null || current == null) return Optional.empty();
        UserId user = currentUserId().orElse(null);
        if (user == null) return Optional.empty();
        Namespace active = current.namespace();
        List<NamespaceDefinition> mine = namespaces.forUser(user);
        String activeLabel = mine.stream().filter(ns -> ns.id().equals(active)).map(NamespaceDefinition::label)
                .findFirst().orElse(active.value());
        return Optional.of(new AdminLayout.NamespaceSwitch(active.value(), activeLabel,
                mine.stream().map(ns -> new AdminLayout.NamespaceSwitch.Entry(ns.id().value(), ns.label())).toList()));
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
        };
    }
}
