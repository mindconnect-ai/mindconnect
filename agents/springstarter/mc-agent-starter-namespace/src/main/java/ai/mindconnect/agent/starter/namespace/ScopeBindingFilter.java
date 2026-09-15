package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.security.SecurityCurrentUserResolver;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses the namespace of a request and binds it, with the caller, to the
 * thread for the rest of the request — the one place a server turns "who is
 * calling what" into the {@link Scope} every routed store asks for.
 *
 * <p>The namespace comes, in this order, from the {@code /ns/{namespace}/}
 * prefix ({@link NamespacePathFilter}), the {@code X-Mindconnect-Namespace}
 * header, and otherwise — for the Admin UI only — the browser session's
 * selection ({@link NamespaceSelection}) or the namespace the user last chose
 * (kept on their {@code User} record, so the choice outlives the session and
 * a restart), and finally the installation's default namespace. A call to
 * the REST API or the Responses API ({@link #API_PREFIXES}) never looks at
 * the session or the remembered choice: it works where it says, else in the
 * default namespace. A namespace named on the
 * request that the caller may not work in is a {@code 403}; a stale session
 * selection is dropped and the default used. A request without a signed-in
 * user — the login page, a static resource — works in the default namespace.
 *
 * <p>Runs after Spring Security, so the authentication is known; a
 * {@link ScopeSupplier} that is not thread-bound (an embedder's fixed one)
 * has nothing to bind and the filter passes the request through.
 */
public class ScopeBindingFilter extends OncePerRequestFilter {

    /** The header an API client names the namespace in, when not using the path prefix. */
    public static final String HEADER = "X-Mindconnect-Namespace";

    private static final Logger log = LoggerFactory.getLogger(ScopeBindingFilter.class);

    private final ScopeSupplier scope;
    private final NamespaceService namespaces;
    /** Where a user's last choice is kept; null when the host has no user records. */
    private final UserService users;

    public ScopeBindingFilter(ScopeSupplier scope, NamespaceService namespaces) {
        this(scope, namespaces, null);
    }

    public ScopeBindingFilter(ScopeSupplier scope, NamespaceService namespaces, UserService users) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.users = users;
    }

    /** The REST API and the Responses API, in front of which no session and no remembered choice count. */
    public static final java.util.List<String> API_PREFIXES = java.util.List.of("/api/", "/v1/");

    /**
     * A program's call, not a browser's: it works in the namespace it names, else in
     * the default one — never in the one the same user last chose in the Admin UI.
     */
    static boolean isApi(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        for (String prefix : API_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The error page renders in the default namespace; a 403 or 400 from above must not repeat itself there. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(scope instanceof ThreadBoundScope bound)) {
            chain.doFilter(request, response);
            return;
        }
        UserId user = SecurityCurrentUserResolver.userIdOf(SecurityContextHolder.getContext().getAuthentication())
                .orElse(null);
        Optional<Namespace> named;
        try {
            named = named(request);
        } catch (IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
            return;
        }
        Namespace namespace;
        if (user == null) {
            namespace = namespaces.defaultNamespace();
        } else if (named.isPresent()) {
            namespace = named.get();
            if (!namespaces.canAccess(user, namespace)) {
                log.debug("{} may not work in namespace '{}'", user.value(), namespace.value());
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "You are not a member of namespace '" + namespace.value() + "'");
                return;
            }
        } else {
            namespace = isApi(request) ? namespaces.defaultNamespace()
                    : chosen(request, user).orElseGet(namespaces::defaultNamespace);
        }
        response.setHeader(HEADER, namespace.value());
        try {
            bound.callIn(Scope.of(namespace, user), () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    /**
     * The namespace the user chose: the session's selection when it is still
     * theirs to use, else the choice kept on their record — put into the
     * session too when there is one, so the record is read once per session,
     * not once per request. A choice they may no longer use is dropped.
     */
    private Optional<Namespace> chosen(HttpServletRequest request, UserId user) {
        Optional<Namespace> selected = NamespaceSelection.selected(request);
        if (selected.isPresent()) {
            if (namespaces.canAccess(user, selected.get())) return selected;
            NamespaceSelection.clear(request);
        }
        if (users == null) return Optional.empty();
        Optional<Namespace> remembered = users.activeNamespace(user);
        if (remembered.isPresent() && namespaces.canAccess(user, remembered.get())) {
            NamespaceSelection.rememberIfSession(request, remembered.get());
            return remembered;
        }
        return Optional.empty();
    }

    /** The namespace the request itself names: the path prefix, else the header. */
    static Optional<Namespace> named(HttpServletRequest request) {
        Object fromPath = request.getAttribute(NamespacePathFilter.ATTRIBUTE);
        if (fromPath instanceof Namespace ns) return Optional.of(ns);
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) return Optional.empty();
        String id = header.strip();
        if (!NamespaceService.ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a namespace: '" + id + "'");
        }
        return Optional.of(new Namespace(id));
    }
}
