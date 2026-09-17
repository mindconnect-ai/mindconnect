package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.security.SecurityCurrentUserResolver;
import ai.mindconnect.namespace.service.NamespaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Objects;

/**
 * The net under the roles: a user of the namespace they are working in reaches
 * the chat and their own things, and nothing that shapes the namespace.
 *
 * <p><strong>It works from a list of what is open, not a list of what is
 * closed.</strong> A route nobody thought about is therefore an admin's — the
 * mistake that costs an afternoon rather than the one that hands a namespace
 * to whoever asks. The price is that a screen meant for users has to be named
 * here, and a test says so out loud.
 *
 * <p>This is a second line, not the authority. What a role may do is decided
 * where it is done ({@code NamespaceService}), and the navigation simply does
 * not offer what a user cannot reach ({@code AdminLayout.chatOnly}). This
 * catches the controller somebody adds without asking either.
 *
 * <p>Admins pass untouched, and so does everything on an installation with no
 * namespace service at all.
 */
public class NamespaceAccessInterceptor implements HandlerInterceptor {

    /**
     * What a user of a namespace may reach. Prefixes, matched against the path
     * without the context path:
     *
     * <ul>
     *   <li>the chat, its API and its stream — what they are here for;</li>
     *   <li>their own account: profile, tokens, their variables, and the
     *       namespaces screen, where they see who they work with and can leave;</li>
     *   <li>the shell itself: the SPA, its assets, the About dialog, the live
     *       stream of their own events, signing out.</li>
     * </ul>
     *
     * <p>The REST API ({@code /api/**}, {@code /v1/**}) is deliberately not in
     * this list: a token carries its owner's rights, so a user of a namespace
     * may chat through it but not create an agent in it either.
     */
    static final List<String> OPEN = List.of(
            "/chat", "/admin/api/chat", "/admin/api/sessions", "/admin/api/messages",
            "/admin/profile", "/admin/api/profile",
            "/admin/namespaces", "/admin/api/namespaces",
            "/admin/api/about", "/admin/api/user-stream", "/admin/api/tasks",
            "/admin/logout", "/logout", "/login", "/index.html", "/login.html",
            "/no-access", "/error", "/favicon.ico",
            "/css/", "/js/", "/sui/", "/sui-ext/", "/img/", "/branding/", "/webjars/",
            "/.well-known/");

    private final NamespaceService namespaces;
    private final ScopeSupplier scope;

    public NamespaceAccessInterceptor(NamespaceService namespaces, ScopeSupplier scope) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = path(request);
        if (isOpen(path)) return true;
        UserId user = signedIn();
        if (user == null) return true;
        Namespace namespace = scope.namespace();
        if (namespaces.isAdmin(user, namespace)) return true;
        response.sendError(HttpServletResponse.SC_FORBIDDEN,
                "Only an admin of '" + namespace.value() + "' may do that");
        return false;
    }

    static boolean isOpen(String path) {
        if (path.equals("/")) return true;
        for (String open : OPEN) {
            if (path.equals(open) || path.startsWith(open.endsWith("/") ? open : open + "/")) return true;
        }
        return false;
    }

    private static String path(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.isEmpty() ? "/" : path;
    }

    /**
     * The user this request runs as — a browser session, a bearer JWT or an API
     * token alike, which is the same answer the scope binding works from.
     */
    private static UserId signedIn() {
        return SecurityCurrentUserResolver.userIdOf(SecurityContextHolder.getContext().getAuthentication())
                .orElse(null);
    }
}
