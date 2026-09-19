package ai.mindconnect.adminui.setup;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import ai.mindconnect.user.service.NotificationService;

/**
 * Puts {@link UserSetupInterceptor} in front of the requests this app handles.
 * The interceptor is always registered; {@link UserSetup} itself does nothing
 * on a host that assembled no {@link NotificationService}, because there would
 * be nowhere to put what the checks find.
 */
@Configuration(proxyBeanMethods = false)
public class UserSetupConfig implements WebMvcConfigurer {

    private final UserSetup setup;

    public UserSetupConfig(UserSetup setup) {
        this.setup = setup;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserSetupInterceptor(setup));
    }
}
