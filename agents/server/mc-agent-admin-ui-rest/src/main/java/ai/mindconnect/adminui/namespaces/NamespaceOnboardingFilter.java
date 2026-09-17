package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.security.SecurityCurrentUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Runs {@link NamespaceOnboarding} on the first request of a session that
 * carries a signed-in user, and turns away somebody who is in no namespace.
 *
 * <p>This application has no login event — see {@link NamespaceOnboarding} —
 * so "the first request after signing in" is what there is. The session
 * remembers that it happened, so the work (two repository reads in the good
 * case) is not repeated per request.
 *
 * <p>Belongs behind the filter that records the user — the address a namespace
 * lists somebody under comes from their user record — and before the scope is
 * bound, which reads the namespace they last chose.
 *
 * <p>Somebody in no namespace is sent to {@link #NO_ACCESS}, once, for a
 * browser navigation; anything else — the SPA's own calls, the API — gets 403.
 * Signing out has to keep working, so it is left open along with what the
 * error page itself needs.
 */
public class NamespaceOnboardingFilter extends OncePerRequestFilter {

    /** Where somebody without a namespace is sent. */
    public static final String NO_ACCESS = "/no-access";

    /** Set on the session once onboarding has run, so it runs once per sign-in. */
    static final String DONE = "mindconnect.namespace.onboarded";

    /**
     * Paths that must work without a namespace: the error page and what it is
     * made of, the way out, and the login round-trip itself.
     */
    private static final List<String> OPEN = List.of(
            NO_ACCESS, "/admin/logout", "/logout", "/login", "/login.html", "/error",
            "/css/", "/js/", "/sui/", "/sui-ext/", "/img/", "/branding/", "/favicon.ico",
            "/.well-known/");

    private final NamespaceOnboarding onboarding;

    public NamespaceOnboardingFilter(NamespaceOnboarding onboarding) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UserId user = signedIn();
        if (user == null || isOpen(request)) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(DONE) != null) {
            chain.doFilter(request, response);
            return;
        }
        NamespaceOnboarding.Outcome outcome = onboarding.onboard(user, request.getServerName());
        if (outcome == NamespaceOnboarding.Outcome.NO_NAMESPACE) {
            turnAway(request, response);
            return;
        }
        HttpSession created = request.getSession(false);
        if (created != null) created.setAttribute(DONE, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    /** The signed-in user, or null when this request carries none. */
    /**
     * The user this request runs as — a browser session, a bearer JWT or an API
     * token alike, which is the same answer the scope binding works from.
     */
    private static UserId signedIn() {
        return SecurityCurrentUserResolver.userIdOf(SecurityContextHolder.getContext().getAuthentication())
                .orElse(null);
    }

    private static boolean isOpen(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isEmpty()) {
            path = path.substring(request.getContextPath().length());
        }
        if (path.equals("/") || path.equals("/index.html")) return false;
        for (String open : OPEN) {
            if (path.equals(open) || path.startsWith(open)) return true;
        }
        return false;
    }

    /** A browser is shown the page; everything else is told 403 and nothing more. */
    private static void turnAway(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            response.sendRedirect(request.getContextPath() + NO_ACCESS);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "No namespace");
    }
}
