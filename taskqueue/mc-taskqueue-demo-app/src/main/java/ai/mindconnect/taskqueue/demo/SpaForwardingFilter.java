package ai.mindconnect.taskqueue.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes deep links bookmarkable in SPA mode.
 *
 * <p>The controllers own {@code /tasks/**} and return JSON. A direct browser
 * hit on a deep link like {@code /tasks/abc123} must instead get the SPA
 * shell, so the client-side bus can read the URL and fetch the page itself.
 * A GET that asks for {@code text/html} on a {@code /tasks} path is forwarded
 * to {@code index.html}; everything else (the bus's JSON fetches, POSTs, the
 * SSE stream under {@code /tasks/api/**}, static assets) flows straight
 * through to the controllers.
 */
@Component
@Order(0)
public class SpaForwardingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (isBrowserNavigation(req)) {
            // The SPA shell shares its URL with the page JSON the bus fetches —
            // without these headers the browser's HTTP cache happily answers
            // the JSON fetch with this cached HTML (same URL, no Vary), and the
            // board silently renders nothing. Devtools masked this in dev by
            // disabling caching; a packaged jar has no such mercy.
            res.setHeader("Vary", "Accept");
            res.setHeader("Cache-Control", "no-store");
            req.getRequestDispatcher("/index.html").forward(req, res);
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean isBrowserNavigation(HttpServletRequest req) {
        if (!"GET".equals(req.getMethod())) return false;

        String path = req.getRequestURI();
        if (!path.equals("/tasks") && !path.startsWith("/tasks/")) {
            return false;
        }
        if (path.startsWith("/tasks/api/")) {
            return false; // stream + command endpoints, never the SPA shell
        }

        String accept = req.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/html");
    }
}
