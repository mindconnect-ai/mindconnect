package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.service.NamespaceService;
import org.springframework.security.access.AccessDeniedException;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Asks, at the last place before something is written, whether whoever this
 * work runs for shapes the namespace it would be written into.
 *
 * <p>The navigation does not offer what a user may not do, and the interceptor
 * refuses those routes — but both are about requests. This is about writes,
 * wherever they come from: a tool an agent called, a task the queue picked up,
 * a controller somebody adds next year without asking either of the other two.
 *
 * <p><strong>Work with no user behind it passes.</strong> Start-up seeding,
 * the initial data loader, a migration running as the installation itself —
 * none of that has somebody to ask about, and refusing it would mean an
 * installation that cannot set itself up. The check is "this user may not",
 * never "nobody may".
 *
 * <p>It throws {@link AccessDeniedException}, so a request that got here
 * anyway ends as 403 rather than 500: Spring Security's translation already
 * knows what to do with it.
 */
public class NamespaceWriteGuard {

    private final Supplier<NamespaceService> namespaces;
    private final Supplier<ScopeSupplier> scope;

    public NamespaceWriteGuard(NamespaceService namespaces, ScopeSupplier scope) {
        Objects.requireNonNull(namespaces, "namespaces");
        Objects.requireNonNull(scope, "scope");
        this.namespaces = () -> namespaces;
        this.scope = () -> scope;
    }

    private NamespaceWriteGuard(Supplier<NamespaceService> namespaces, Supplier<ScopeSupplier> scope) {
        this.namespaces = namespaces;
        this.scope = scope;
    }

    /**
     * A guard that finds the namespace service and the scope at the write,
     * for a caller built before either exists. While there is neither there
     * are no namespaces to have roles in, and every write passes.
     */
    public static NamespaceWriteGuard deferred(Supplier<NamespaceService> namespaces, Supplier<ScopeSupplier> scope) {
        return new NamespaceWriteGuard(Objects.requireNonNull(namespaces, "namespaces"),
                Objects.requireNonNull(scope, "scope"));
    }

    /**
     * Lets the write happen, or refuses it.
     *
     * @param what what is being written, for the message: "an agent", "a workflow"
     * @throws AccessDeniedException when the scope has a user who is not an admin
     *                               of the namespace the write would land in
     */
    public void requireAdmin(String what) {
        NamespaceService namespaces = this.namespaces.get();
        ScopeSupplier scope = this.scope.get();
        if (namespaces == null || scope == null) return;
        Scope current = scope.get();
        UserId user = current.user();
        if (user == null) return;
        Namespace namespace = current.namespace();
        if (namespaces.isAdmin(user, namespace)) return;
        throw new AccessDeniedException("Only an admin of '" + namespace.value()
                + "' may change " + what + " there");
    }
}
