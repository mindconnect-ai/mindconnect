package ai.mindconnect.adminui.ui;

import ai.mindconnect.extension.service.ExtensionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Optional;

/**
 * An extension switched off in the namespace at hand takes its screens with
 * it: a request to a route its manifest names is answered 404, so a
 * bookmark does not reach a page whose menu entry has gone. Whether the
 * caller may open the route at all is the access interceptor's question,
 * asked before this one.
 */
@Configuration(proxyBeanMethods = false)
public class ExtensionRouteConfig implements WebMvcConfigurer {

    private final ExtensionService extensions;

    public ExtensionRouteConfig(ExtensionService extensions) {
        this.extensions = extensions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Guard(extensions));
    }

    static final class Guard implements HandlerInterceptor {
        private final ExtensionService extensions;

        Guard(ExtensionService extensions) {
            this.extensions = extensions;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws java.io.IOException {
            Optional<ExtensionService.Status> owner;
            try {
                owner = extensions.routeOwner(request.getRequestURI());
            } catch (IllegalStateException noScope) {
                return true;   // nothing bound, nothing decided
            }
            if (owner.isPresent() && !owner.get().enabled()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                        "'" + owner.get().manifest().name() + "' is switched off in this namespace");
                return false;
            }
            return true;
        }
    }
}
