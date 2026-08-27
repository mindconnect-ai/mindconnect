package ai.mindconnect.workflow.admin.app;

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
 * <p>The {@code WorkflowAdminUiController} owns {@code /workflow-admin/**} and
 * returns JSON. A direct browser hit on a deep link like
 * {@code /workflow-admin/hello} must instead get the SPA shell, so the
 * client-side bus can read the URL and fetch the page itself.
 *
 * <p>Letting Spring decide via {@code produces} doesn't work: the specific
 * controller path ({@code /{wf}}) always wins over a generic SPA wildcard
 * before content negotiation runs. So we intercept earlier, in a servlet
 * filter: a GET that asks for {@code text/html} on a {@code /workflow-admin}
 * path is forwarded to {@code index.html}; everything else (the bus's JSON
 * fetches, POSTs, static assets) flows straight through to the controllers.
 */
@Component
@Order(0)
public class SpaForwardingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (isBrowserNavigation(req)) {
            req.getRequestDispatcher("/index.html").forward(req, res);
            return;
        }
        chain.doFilter(req, res);
    }

    private boolean isBrowserNavigation(HttpServletRequest req) {
        if (!"GET".equals(req.getMethod())) return false;

        String path = req.getRequestURI();
        if (!path.equals("/workflow-admin") && !path.startsWith("/workflow-admin/")) {
            return false;
        }

        // A browser navigation accepts HTML; the SPA bus fetches with
        // Accept: application/json, which must pass through to the controller.
        String accept = req.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/html");
    }
}
