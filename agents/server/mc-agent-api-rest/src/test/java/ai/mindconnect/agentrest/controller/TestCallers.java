package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import ai.mindconnect.agentrest.auth.CurrentUserWebConfig;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The authenticated caller of standalone MockMvc requests, switchable between
 * requests — what the host's security layer establishes in the running app.
 */
class TestCallers {

    private final AtomicReference<UserId> current = new AtomicReference<>();

    void actAs(String user) {
        current.set(UserId.of(user));
    }

    /** The {@code @CurrentUser} resolver, answering with whoever {@link #actAs} named last. */
    HandlerMethodArgumentResolver resolver() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("resolver", (CurrentUserResolver) () -> Optional.ofNullable(current.get()));
        return new CurrentUserWebConfig.Resolver(
                new CurrentUsers(beans.getBeanProvider(CurrentUserResolver.class), true, "dev"));
    }
}
