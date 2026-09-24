package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.namespace.service.NamespaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts {@link NamespaceAccessInterceptor} in front of every request this app
 * handles — but only where there are namespaces to have roles in. A host that
 * embeds the Admin UI without the namespace starter keeps the behaviour it had
 * before roles existed: whoever is in, shapes.
 *
 * <p>The routes an extension's manifest opens to users come from the
 * {@link ExtensionService}, when there is one.
 *
 * <p>Whether there are namespaces is asked at the request, not as a condition
 * on this class. This configuration is found by component scanning, which runs
 * before the auto-configurations that define {@link NamespaceService} and
 * {@link ScopeSupplier}; a {@code @ConditionalOnBean} here saw neither, so the
 * guard was never installed and a plain member reached every admin route.
 */
@Configuration(proxyBeanMethods = false)
public class NamespaceAccessConfig implements WebMvcConfigurer {

    private final ObjectProvider<NamespaceService> namespaces;
    private final ObjectProvider<ScopeSupplier> scope;
    private final ObjectProvider<ExtensionService> extensions;

    public NamespaceAccessConfig(ObjectProvider<NamespaceService> namespaces, ObjectProvider<ScopeSupplier> scope,
                                 ObjectProvider<ExtensionService> extensions) {
        this.namespaces = namespaces;
        this.scope = scope;
        this.extensions = extensions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Deferred());
    }

    /**
     * The guard, built on the first request that finds both beans — by then the
     * context is complete. Without them there is nothing to guard and every
     * request passes, as it did before namespaces existed.
     */
    final class Deferred implements HandlerInterceptor {

        private volatile NamespaceAccessInterceptor guard;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
            NamespaceAccessInterceptor current = guard();
            return current == null || current.preHandle(request, response, handler);
        }

        NamespaceAccessInterceptor guard() {
            NamespaceAccessInterceptor current = guard;
            if (current != null) return current;
            NamespaceService service = namespaces.getIfAvailable();
            ScopeSupplier supplier = scope.getIfAvailable();
            if (service == null || supplier == null) return null;
            current = new NamespaceAccessInterceptor(service, supplier, NamespaceAccessConfig.this::opensToUsers);
            guard = current;
            return current;
        }
    }

    private boolean opensToUsers(String path) {
        ExtensionService service = extensions.getIfAvailable();
        if (service == null) return false;
        try {
            return service.opensToUsers(path);
        } catch (IllegalStateException noScope) {
            return false;   // nothing bound, nothing opened
        }
    }
}
