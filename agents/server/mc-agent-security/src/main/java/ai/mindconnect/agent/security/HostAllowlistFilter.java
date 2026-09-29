package ai.mindconnect.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refuses every request whose {@code Host} header is not on the
 * {@link HostAllowlist} — with {@code 403} and a line naming the property to
 * set, before Spring Security or any controller sees it. See the allow-list
 * for what this defends against.
 *
 * <p>The header is read raw, not through {@code request.getServerName()}: with
 * {@code server.forward-headers-strategy} on, that one follows
 * {@code X-Forwarded-Host}, which a page may set on a cross-origin request
 * when CORS lets every header through — and the point here is to trust
 * nothing a page controls.
 *
 * <p>A refused host is logged once, so a wrong list is found in the log
 * without a rejected browser flooding it.
 */
public class HostAllowlistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HostAllowlistFilter.class);
    private static final int MAX_LOGGED_HOSTS = 50;

    private final HostAllowlist allowed;
    private final String property;
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    /**
     * @param allowed  the hosts to let through
     * @param property the configuration property the refusal names, e.g.
     *                 {@code mindconnect.security.allowed-hosts}
     */
    public HostAllowlistFilter(HostAllowlist allowed, String property) {
        this.allowed = allowed;
        this.property = property;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader(HttpHeaders.HOST);
        if (allowed.allows(host, request.isSecure())) {
            chain.doFilter(request, response);
            return;
        }
        logOnce(host, request);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getWriter().write("This server does not answer to the host name the request used. "
                + "If it should, add the name to " + property + " (allowed now: " + allowed + ").\n");
    }

    private void logOnce(String host, HttpServletRequest request) {
        String shown = host == null ? "<none>" : host.replaceAll("[^\\x20-\\x7e]", "?");
        if (shown.length() > 200) shown = shown.substring(0, 200) + "…";
        if (logged.size() >= MAX_LOGGED_HOSTS || !logged.add(shown)) return;
        log.warn("Refused a request with Host \"{}\" (Origin {}, {} {}): not in {} = [{}]. "
                        + "A browser page pointing its own domain at this machine looks exactly like this; "
                        + "if the name is yours, add it to the property.",
                shown, request.getHeader(HttpHeaders.ORIGIN), request.getMethod(), request.getRequestURI(),
                property, allowed);
    }
}
