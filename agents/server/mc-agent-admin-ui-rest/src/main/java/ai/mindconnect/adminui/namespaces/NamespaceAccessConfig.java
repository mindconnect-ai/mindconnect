package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.namespace.service.NamespaceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts {@link NamespaceAccessInterceptor} in front of every request this app
 * handles — but only where there are namespaces to have roles in. A host that
 * embeds the Admin UI without the namespace starter keeps the behaviour it had
 * before roles existed: whoever is in, shapes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean({NamespaceService.class, ScopeSupplier.class})
public class NamespaceAccessConfig implements WebMvcConfigurer {

    private final NamespaceService namespaces;
    private final ScopeSupplier scope;

    public NamespaceAccessConfig(NamespaceService namespaces, ScopeSupplier scope) {
        this.namespaces = namespaces;
        this.scope = scope;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NamespaceAccessInterceptor(namespaces, scope));
    }
}
