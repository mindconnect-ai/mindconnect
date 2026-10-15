package ai.mindconnect.adminui.ui;

import ai.mindconnect.adminui.namespaces.NamespaceAccessInterceptor;
import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.adapter.memory.InMemoryExtensionActivationRepository;
import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An extension's REST API under {@code /api/<id>/} is a route of its own:
 * the manifest gates it by role for the namespace's plain users, a
 * switched-off extension takes it down with a 404, and — being JSON for
 * scripts — it is never wrapped in the admin layout. The pieces are the ones
 * the app chains for every request: the namespace guard (fed by
 * {@link ExtensionService#opensToUsers}, as {@code NamespaceAccessConfig}
 * does), the route guard of {@link ExtensionRouteConfig}, and
 * {@link ExtensionPageAdvice}.
 */
class ExtensionApiRouteTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Namespace ACME = new Namespace("acme");
    private static final UserId DAVID = UserId.of("david");   // admin of ACME
    private static final UserId ALICE = UserId.of("alice");   // plain user of ACME
    private static final ExtensionId SCHEDULER = ExtensionId.of("scheduler");

    private final InMemoryExtensionActivationRepository decisions = new InMemoryExtensionActivationRepository();
    private final ExtensionService extensions = new ExtensionService(new ExtensionRegistry(List.of(
            new Extension(new ExtensionManifest(SCHEDULER, "Scheduler", "1.0", null, null, null, null, true, null,
                    new ExtensionManifest.Contributes(null, null, null, new ExtensionManifest.Ui(null, List.of(
                            new ExtensionManifest.Ui.Route("/admin/scheduler/**", List.of("ADMIN")),
                            new ExtensionManifest.Ui.Route("/api/scheduler/jobs/**", List.of("ADMIN", "USER")),
                            new ExtensionManifest.Ui.Route("/api/scheduler/admin/**", List.of("ADMIN"))), null),
                            null, null, null, null)), "scheduler.jar"))), decisions);

    private final NamespaceService namespaces = new NamespaceService(new InMemoryNamespaceRepository(),
            Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC), List.of(),
            id -> Optional.of(Email.of(id.value() + "@acme.example")), "acme.example", List.of());
    private final NamespaceAccessInterceptor access = new NamespaceAccessInterceptor(namespaces,
            ScopeSupplier.fixed(Scope.of(ACME, DAVID)), extensions::opensToUsers);
    private final ExtensionRouteConfig.Guard routes = new ExtensionRouteConfig.Guard(extensions);

    ExtensionApiRouteTest() {
        namespaces.create("acme", "ACME", DAVID);
        namespaces.invite(ACME, DAVID, Email.of("alice@acme.example"), NamespaceRole.USER);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void a_plain_user_reaches_the_api_route_the_manifest_opens_to_users_and_nothing_else() throws Exception {
        signedIn(ALICE);
        assertThat(status("/api/scheduler/jobs")).isEqualTo(200);
        assertThat(status("/api/scheduler/jobs/42/run")).isEqualTo(200);
        assertThat(status("/api/scheduler/admin/purge")).as("the admin-only part of the same API").isEqualTo(403);
        assertThat(status("/admin/scheduler")).as("the admin's screen").isEqualTo(403);
        assertThat(status("/api/jobs")).as("the shared /api space is not the extension's").isEqualTo(403);

        signedIn(UserId.of("mallory"));
        assertThat(status("/api/scheduler/jobs")).as("open to the namespace's users, not to anybody signed in")
                .isEqualTo(403);
    }

    @Test
    void an_admin_reaches_every_part_of_it() throws Exception {
        signedIn(DAVID);
        assertThat(status("/api/scheduler/jobs")).isEqualTo(200);
        assertThat(status("/api/scheduler/admin/purge")).isEqualTo(200);
    }

    @Test
    void switched_off_in_the_namespace_the_api_answers_404() throws Exception {
        extensions.disable(SCHEDULER, DAVID);

        signedIn(DAVID);
        assertThat(status("/api/scheduler/jobs")).isEqualTo(404);
        assertThat(status("/api/scheduler/admin/purge")).isEqualTo(404);
        assertThat(status("/admin/scheduler")).isEqualTo(404);
        assertThat(status("/api/agents")).as("nobody's route stays up").isEqualTo(200);

        signedIn(ALICE);
        assertThat(status("/api/scheduler/jobs")).as("no longer opened to users either").isEqualTo(403);
    }

    @Test
    void what_an_api_route_answers_is_never_wrapped_in_the_admin_layout() {
        ExtensionPageAdvice advice = new ExtensionPageAdvice(new AdminLayoutFactory(false,
                new BuildInfo(Optional.empty(), Optional.empty()), Optional.empty(), none(), none()), extensions);

        UiPage onApi = UiPage.of("/api/scheduler/jobs", UiStack.of("body"));
        assertThat(advice.beforeBodyWrite(onApi, null, null, null, request("/api/scheduler/jobs"), null))
                .isSameAs(onApi);

        UiPage onScreen = UiPage.of("/admin/scheduler", UiStack.of("body"));
        Object wrapped = advice.beforeBodyWrite(onScreen, null, null, null, request("/admin/scheduler"), null);
        assertThat(((UiPage) wrapped).getNode().getId()).as("the screen of the same extension is")
                .isEqualTo(AdminLayoutAdvice.LAYOUT_ID);
    }

    /** The status the two guards leave, in the order the app runs them: roles first, then on/off. */
    private int status(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (access.preHandle(request, response, new Object())) {
            routes.preHandle(request, response, new Object());
        }
        return response.getStatus();
    }

    private static ServletServerHttpRequest request(String path) {
        return new ServletServerHttpRequest(new MockHttpServletRequest("GET", path));
    }

    private static void signedIn(UserId user) {
        OidcIdToken token = OidcIdToken.withTokenValue("id").subject("sub-" + user.value())
                .claim(StandardClaimNames.PREFERRED_USERNAME, user.value())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        DefaultOidcUser principal = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    /** An ObjectProvider with nothing in it — the factory asks it only {@code getIfAvailable}. */
    private static <T> ObjectProvider<T> none() {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return null; }
        };
    }
}
