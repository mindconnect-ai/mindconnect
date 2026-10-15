package ai.mindconnect.agentrest.auth;

import ai.mindconnect.agent.UserId;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Resolves {@link CurrentUser} parameters from {@link CurrentUsers}, and keeps
 * them out of the OpenAPI description — a client does not send the caller, it
 * authenticates as the caller.
 */
@Configuration
public class CurrentUserWebConfig implements WebMvcConfigurer {

    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    private final CurrentUsers currentUsers;

    public CurrentUserWebConfig(CurrentUsers currentUsers) {
        this.currentUsers = currentUsers;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new Resolver(currentUsers));
    }

    /** Public for standalone MockMvc setups, which register argument resolvers by hand. */
    public static class Resolver implements HandlerMethodArgumentResolver {

        private final CurrentUsers currentUsers;

        public Resolver(CurrentUsers currentUsers) {
            this.currentUsers = currentUsers;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CurrentUser.class)
                    && UserId.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return currentUsers.require();
        }
    }
}
