package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds the namespace of {@code /api/ns/{namespace}/…} for the request, so the
 * stores answer from that namespace. The ports take no namespace yet; once
 * they do, the controllers pass the path variable on and this filter goes.
 */
@Component
class NamespaceFilter extends OncePerRequestFilter {

    private static final Pattern NAMESPACED = Pattern.compile("^/api/ns/([^/]+)(/.*)?$");

    private final ThreadBoundScopeSupplier scope;

    NamespaceFilter(ThreadBoundScopeSupplier scope) {
        this.scope = scope;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher namespaced = NAMESPACED.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (!namespaced.matches()) {
            chain.doFilter(request, response);
            return;
        }
        Scope bound = Scope.of(new Namespace(namespaced.group(1)));
        try {
            scope.callIn(bound, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}
