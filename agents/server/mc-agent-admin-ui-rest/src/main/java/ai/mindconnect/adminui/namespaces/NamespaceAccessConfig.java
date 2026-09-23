package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.namespace.service.NamespaceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
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
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({NamespaceService.class, ScopeSupplier.class})
public class NamespaceAccessConfig implements WebMvcConfigurer {

    private final NamespaceService namespaces;
    private final ScopeSupplier scope;
    private final ObjectProvider<ExtensionService> extensions;

    public NamespaceAccessConfig(NamespaceService namespaces, ScopeSupplier scope,
                                 ObjectProvider<ExtensionService> extensions) {
        this.namespaces = namespaces;
        this.scope = scope;
        this.extensions = extensions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NamespaceAccessInterceptor(namespaces, scope, this::opensToUsers));
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
