package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard is installed whether or not the namespace beans exist yet when the
 * configuration is built — they come from auto-configurations, after the
 * scan — and it finds them at the first request.
 */
class NamespaceAccessConfigTest {

    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private HandlerInterceptor installed() {
        var config = new NamespaceAccessConfig(beans.getBeanProvider(NamespaceService.class),
                beans.getBeanProvider(ScopeSupplier.class), beans.getBeanProvider(ExtensionService.class));
        var registry = new InterceptorRegistry() {
            HandlerInterceptor last;

            @Override
            public org.springframework.web.servlet.config.annotation.InterceptorRegistration addInterceptor(
                    HandlerInterceptor interceptor) {
                last = interceptor;
                return super.addInterceptor(interceptor);
            }
        };
        config.addInterceptors(registry);
        assertThat(registry.last).isNotNull();
        return registry.last;
    }

    private static void signedIn(String user) {
        Instant now = Instant.now();
        OidcIdToken token = OidcIdToken.withTokenValue("id").subject("sub-" + user)
                .claim(StandardClaimNames.PREFERRED_USERNAME, user)
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        DefaultOidcUser principal = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    private static boolean allowed(HandlerInterceptor guard, String path) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        var response = new MockHttpServletResponse();
        return guard.preHandle(request, response, new Object());
    }

    @Test
    void without_namespaces_everything_passes() throws Exception {
        HandlerInterceptor guard = installed();
        signedIn("alice");
        assertThat(allowed(guard, "/admin/agents")).isTrue();
    }

    @Test
    void namespaces_that_arrive_after_the_configuration_are_guarded() throws Exception {
        HandlerInterceptor guard = installed();          // built before the beans exist, as in the real app

        Namespace acme = new Namespace("acme");
        NamespaceService namespaces = new NamespaceService(new InMemoryNamespaceRepository(), Namespace.DEFAULT,
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC), List.of(),
                id -> Optional.of(Email.of(id.value() + "@acme.example")), "acme.example", List.of());
        beans.addBean("namespaceService", namespaces);
        beans.addBean("scopeSupplier", ScopeSupplier.fixed(Scope.of(acme, UserId.of("alice"))));

        signedIn("alice");
        assertThat(allowed(guard, "/admin/agents")).as("a stranger to 'acme' on an admin route").isFalse();
        assertThat(allowed(guard, "/chat")).as("an open route").isTrue();
    }
}
