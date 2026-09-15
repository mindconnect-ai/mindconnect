package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.namespace.service.NamespaceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lets an API client name the namespace in the address:
 * {@code /ns/{namespace}/api/v1/…} is {@code /api/v1/…} in namespace
 * {@code {namespace}}. The prefix is cut off before Spring Security and
 * Spring MVC see the request, so every matcher and every controller works
 * on the path it always had; the namespace travels on as a request
 * attribute for {@link ScopeBindingFilter}, which runs after authentication
 * and checks that the caller may work there.
 *
 * <p>Why a prefix and not the first segment: a namespace called {@code api}
 * or {@code ui} must not shadow the server's own routes. Why the address at
 * all: an OpenAI-compatible client configures a base URL and nothing else, so
 * {@code https://host/ns/acme} is how such a client reaches namespace
 * {@code acme}. A malformed id is left alone and answered by whatever serves
 * {@code /ns/…} — nothing, that is, a 404.
 */
public class NamespacePathFilter extends OncePerRequestFilter {

    /** The prefix, without trailing slash. */
    public static final String PREFIX = "/ns";
    /** Request attribute the namespace of the prefix is stored under. */
    public static final String ATTRIBUTE = NamespacePathFilter.class.getName() + ".namespace";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        if (!path.startsWith(PREFIX + "/")) {
            chain.doFilter(request, response);
            return;
        }
        int end = path.indexOf('/', PREFIX.length() + 1);
        String id = end < 0 ? path.substring(PREFIX.length() + 1) : path.substring(PREFIX.length() + 1, end);
        if (!NamespaceService.ID.matcher(id).matches()) {
            chain.doFilter(request, response);
            return;
        }
        String rest = end < 0 ? "/" : path.substring(end);
        request.setAttribute(ATTRIBUTE, new Namespace(id));
        chain.doFilter(new Stripped(request, contextPath, rest), response);
    }

    /** The request as it looks without the prefix. */
    static final class Stripped extends HttpServletRequestWrapper {

        private final String contextPath;
        private final String path;

        Stripped(HttpServletRequest request, String contextPath, String path) {
            super(request);
            this.contextPath = contextPath;
            this.path = path;
        }

        @Override
        public String getRequestURI() {
            return contextPath + path;
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getPathInfo() {
            return null;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer(getScheme()).append("://").append(getServerName());
            int port = getServerPort();
            boolean standard = ("http".equals(getScheme()) && port == 80) || ("https".equals(getScheme()) && port == 443);
            if (port > 0 && !standard) url.append(':').append(port);
            return url.append(getRequestURI());
        }
    }
}
