package ai.mindconnect.adminui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * One URL per page, decided by content negotiation — no client-side URL
 * rewriting (app.js carries no mapping table):
 *
 * <ul>
 *   <li>GET + {@code Accept: text/html} on any admin section is a browser
 *       navigation → forward to the SPA shell ({@code /index.html}); the
 *       event bus then fetches the same URL as JSON.</li>
 *   <li>GET without HTML on a <em>legacy</em> section whose controller still
 *       lives under {@code /admin/api/**} → forward there server-side.</li>
 *   <li>Same-URL sections ({@code /workflow-admin}, {@code /admin/vector-stores})
 *       serve JSON on the page URL directly — new sections should follow that
 *       pattern; the legacy list below shrinks as controllers migrate.</li>
 * </ul>
 */
@Component
@Order(0)
public class AdminSameUrlFilter extends org.springframework.web.filter.OncePerRequestFilter {

    /** Sections whose controllers already serve JSON on the page URL. */
    private static final List<String> SAME_URL_SECTIONS = List.of(
            "/workflow-admin", "/admin/vector-stores");

    /** Sections whose controllers still live under /admin/api/<section>. */
    private static final List<String> LEGACY_SECTIONS = List.of(
            "/admin/agents", "/admin/sessions", "/admin/tools",
            "/admin/llm-configs", "/admin/migrations", "/admin/api-explorer");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if ("GET".equals(req.getMethod())) {
            String path = req.getRequestURI();
            boolean sameUrl = matches(path, SAME_URL_SECTIONS);
            boolean legacy = matches(path, LEGACY_SECTIONS);
            if (sameUrl || legacy) {
                if (wantsHtml(req)) {
                    req.getRequestDispatcher("/index.html").forward(req, res);
                    return;
                }
                if (legacy) {
                    // /admin/agents/... → /admin/api/agents/... (query params survive the forward)
                    req.getRequestDispatcher("/admin/api" + path.substring("/admin".length()))
                            .forward(req, res);
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    private static boolean matches(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    private static boolean wantsHtml(HttpServletRequest req) {
        String accept = req.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/html");
    }
}
