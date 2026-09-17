package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeBindingFilterTest {

    private static final Namespace ACME = new Namespace("acme");

    private final ThreadBoundScope bound = ThreadBoundScope.strict();
    private final NamespaceService namespaces = new NamespaceService(new InMemoryNamespaceRepository(), Namespace.DEFAULT);
    private final ScopeBindingFilter filter = new ScopeBindingFilter(bound, namespaces);
    private final AtomicReference<Scope> seen = new AtomicReference<>();
    private final MockFilterChain chain = freshChain();

    /** A chain records one call only: a test that filters several requests takes a fresh one each time. */
    private MockFilterChain freshChain() {
        return new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                seen.set(bound.get());
            }
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String user) {
        OidcIdToken token = OidcIdToken.withTokenValue("t").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .subject(user).claim(StandardClaimNames.PREFERRED_USERNAME, user).build();
        DefaultOidcUser principal = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), token);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws ServletException, java.io.IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void anAnonymousRequestWorksInTheDefaultNamespace() throws Exception {
        run(new MockHttpServletRequest("GET", "/login"));

        assertThat(seen.get()).isEqualTo(Scope.of(Namespace.DEFAULT));
        assertThat(bound.isBound()).isFalse();
    }

    @Test
    void aSignedInUserWithoutAChoiceWorksInTheDefaultNamespaceOnTheirOwnBehalf() throws Exception {
        signIn("david");

        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/admin/api/agents"));

        assertThat(seen.get()).isEqualTo(Scope.of(Namespace.DEFAULT, UserId.of("david")));
        assertThat(response.getHeader(ScopeBindingFilter.HEADER)).isEqualTo("local");
    }

    @Test
    void thePathPrefixWinsForAMember() throws Exception {
        namespaces.create("acme", null, UserId.of("david"));
        signIn("david");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sessions");
        request.setAttribute(NamespacePathFilter.ATTRIBUTE, ACME);

        run(request);

        assertThat(seen.get()).isEqualTo(Scope.of(ACME, UserId.of("david")));
    }

    @Test
    void theHeaderNamesTheNamespaceToo() throws Exception {
        namespaces.create("acme", null, UserId.of("david"));
        signIn("david");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/responses");
        request.addHeader(ScopeBindingFilter.HEADER, "acme");

        run(request);

        assertThat(seen.get().namespace()).isEqualTo(ACME);
    }

    @Test
    void aHeaderThatIsNoNamespaceIsA400NotA500() throws Exception {
        signIn("david");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sessions");
        request.addHeader(ScopeBindingFilter.HEADER, "Not A Namespace");

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(seen.get()).isNull();
    }

    @Test
    void aNonMemberNamingANamespaceGets403() throws Exception {
        namespaces.create("acme", null, UserId.of("david"));
        signIn("alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sessions");
        request.setAttribute(NamespacePathFilter.ATTRIBUTE, ACME);

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(seen.get()).isNull();
    }

    @Test
    void theSessionSelectionIsHonouredForAMember() throws Exception {
        namespaces.create("acme", null, UserId.of("david"));
        signIn("david");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/agents");
        NamespaceSelection.select(request, ACME);

        run(request);

        assertThat(seen.get().namespace()).isEqualTo(ACME);
        assertThat(NamespaceSelection.selected(request)).contains(ACME);
    }

    @Test
    void aStaleSelectionFallsBackToTheDefaultAndIsForgotten() throws Exception {
        signIn("alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/agents");
        NamespaceSelection.select(request, ACME);          // alice was never invited

        run(request);

        assertThat(seen.get().namespace()).isEqualTo(Namespace.DEFAULT);
        assertThat(NamespaceSelection.selected(request)).isEmpty();
    }

    @Test
    void anApiCallNeverFollowsTheSessionOrTheRememberedChoice() throws Exception {
        namespaces.create("acme", null, UserId.of("david"));
        ai.mindconnect.user.service.UserService users = new ai.mindconnect.user.service.UserService(
                new ai.mindconnect.user.adapter.memory.InMemoryUserRepository());
        users.selectNamespace(UserId.of("david"), ACME);
        ScopeBindingFilter remembering = new ScopeBindingFilter(bound, namespaces, users);
        signIn("david");

        for (String path : new String[]{"/api/v1/sessions", "/v1/responses"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            NamespaceSelection.select(request, ACME);
            remembering.doFilter(request, new MockHttpServletResponse(), freshChain());
            assertThat(seen.get().namespace()).as(path).isEqualTo(Namespace.DEFAULT);
        }

        MockHttpServletRequest named = new MockHttpServletRequest("POST", "/v1/responses");
        named.addHeader(ScopeBindingFilter.HEADER, "acme");
        remembering.doFilter(named, new MockHttpServletResponse(), freshChain());
        assertThat(seen.get().namespace()).isEqualTo(ACME);
    }

    @Test
    void theRememberedChoiceOnTheUserRecordIsUsedWhenTheSessionKnowsNothing() throws Exception {
        namespaces.create("acme", null, UserId.of("david"));
        ai.mindconnect.user.service.UserService users = new ai.mindconnect.user.service.UserService(
                new ai.mindconnect.user.adapter.memory.InMemoryUserRepository());
        users.selectNamespace(UserId.of("david"), ACME);
        ScopeBindingFilter remembering = new ScopeBindingFilter(bound, namespaces, users);
        signIn("david");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/agents");
        request.getSession(true);                          // a session that holds no selection yet

        MockHttpServletResponse response = new MockHttpServletResponse();
        remembering.doFilter(request, response, chain);

        assertThat(seen.get().namespace()).isEqualTo(ACME);
        assertThat(NamespaceSelection.selected(request)).contains(ACME);   // primed for the next request
    }

    @Test
    void aRememberedChoiceTheUserMayNoLongerUseIsIgnored() throws Exception {
        ai.mindconnect.user.service.UserService users = new ai.mindconnect.user.service.UserService(
                new ai.mindconnect.user.adapter.memory.InMemoryUserRepository());
        users.selectNamespace(UserId.of("alice"), ACME);   // acme does not even exist
        ScopeBindingFilter remembering = new ScopeBindingFilter(bound, namespaces, users);
        signIn("alice");

        remembering.doFilter(new MockHttpServletRequest("GET", "/admin/api/agents"), new MockHttpServletResponse(), chain);

        assertThat(seen.get().namespace()).isEqualTo(Namespace.DEFAULT);
    }

    @Test
    void anInstallationThatClosedItsDefaultNamespaceRefusesSomebodyItListsNowhere() throws Exception {
        NamespaceService closed = new NamespaceService(new InMemoryNamespaceRepository(), Namespace.DEFAULT,
                java.time.Clock.systemUTC(), List.of(), id -> java.util.Optional.empty(), "local",
                List.of(ai.mindconnect.agent.Email.of("chief@example.com")));
        ScopeBindingFilter guarded = new ScopeBindingFilter(bound, closed);
        signIn("stranger");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guarded.doFilter(request, response, chain);

        assertThat(response.getStatus()).as("no namespace, no work — not even through the API").isEqualTo(403);
        assertThat(seen.get()).isNull();
    }

    @Test
    void whoeverIsListedInTheClosedDefaultNamespaceWorksThereAsBefore() throws Exception {
        NamespaceService closed = new NamespaceService(new InMemoryNamespaceRepository(), Namespace.DEFAULT,
                java.time.Clock.systemUTC(), List.of(), id -> java.util.Optional.empty(), "example.com",
                List.of(ai.mindconnect.agent.Email.of("chief@example.com")));
        ScopeBindingFilter guarded = new ScopeBindingFilter(bound, closed);
        signIn("chief");

        guarded.doFilter(new MockHttpServletRequest("GET", "/api/agents"), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNotNull()
                .satisfies(scope -> assertThat(scope.namespace()).isEqualTo(Namespace.DEFAULT));
    }

    /** A host that stands for the namespace "erni", as the Admin UI's branding answers it. */
    private static final HostNamespaces ERNI_HOST = new HostNamespaces() {
        @Override public java.util.Optional<Namespace> namespaceOf(String host) {
            return "erni.example.com".equals(host) ? java.util.Optional.of(new Namespace("erni"))
                    : java.util.Optional.empty();
        }

        @Override public java.util.Set<Namespace> bound() {
            return java.util.Set.of(new Namespace("erni"));
        }
    };

    private MockHttpServletRequest under(String host, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        return request;
    }

    @Test
    void aHostThatStandsForANamespaceIsThatNamespace_whateverTheUserChoseBefore() throws Exception {
        namespaces.create("erni", "ERNI AI", UserId.of("david"));
        ScopeBindingFilter byHost = new ScopeBindingFilter(bound, namespaces, null, ERNI_HOST);
        signIn("david");
        MockHttpServletRequest request = under("erni.example.com", "/chat");
        NamespaceSelection.select(request, Namespace.DEFAULT);

        byHost.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNotNull()
                .satisfies(scope -> assertThat(scope.namespace()).isEqualTo(new Namespace("erni")));
    }

    @Test
    void underThatHostSomebodyWhoIsNotInItDoesNotGetIn_evenWithANamespaceOfTheirOwn() throws Exception {
        namespaces.create("erni", "ERNI AI", UserId.of("david"));
        namespaces.create("alice", "Alice", UserId.of("alice"));
        ScopeBindingFilter byHost = new ScopeBindingFilter(bound, namespaces, null, ERNI_HOST);
        signIn("alice");
        MockHttpServletResponse response = new MockHttpServletResponse();

        byHost.doFilter(under("erni.example.com", "/chat"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).contains("not in any namespace of this installation");
        assertThat(seen.get()).as("and their own namespace is no way in here").isNull();
    }

    @Test
    void aHeaderDoesNotMoveTheWorkAwayFromTheHostThatNamesIt() throws Exception {
        namespaces.create("erni", "ERNI AI", UserId.of("david"));
        namespaces.create("david", "David", UserId.of("david"));
        ScopeBindingFilter byHost = new ScopeBindingFilter(bound, namespaces, null, ERNI_HOST);
        signIn("david");
        MockHttpServletRequest request = under("erni.example.com", "/api/agents");
        request.addHeader(ScopeBindingFilter.HEADER, "david");
        MockHttpServletResponse response = new MockHttpServletResponse();

        byHost.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).contains("does not serve namespace 'david'");
    }

    @Test
    void aHostThatBindsNothingLeavesTheChoiceWhereItWas() throws Exception {
        namespaces.create("david", "David", UserId.of("david"));
        ScopeBindingFilter byHost = new ScopeBindingFilter(bound, namespaces, null, ERNI_HOST);
        signIn("david");
        MockHttpServletRequest request = under("app.example.com", "/chat");
        NamespaceSelection.select(request, new Namespace("david"));

        byHost.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNotNull()
                .satisfies(scope -> assertThat(scope.namespace()).isEqualTo(new Namespace("david")));
    }

    @Test
    void awayFromItsOwnAddressABoundNamespaceIsNotReachable_notEvenAsTheChoiceFromBefore() throws Exception {
        namespaces.create("erni", "ERNI AI", UserId.of("david"));
        namespaces.create("david", "David", UserId.of("david"));
        ScopeBindingFilter byHost = new ScopeBindingFilter(bound, namespaces, null, ERNI_HOST);
        signIn("david");
        MockHttpServletRequest request = under("app.example.com", "/chat");
        NamespaceSelection.select(request, new Namespace("erni"));

        byHost.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNotNull()
                .satisfies(scope -> assertThat(scope.namespace())
                        .as("the work of one brand does not appear under another's name")
                        .isEqualTo(Namespace.DEFAULT));
        assertThat(NamespaceSelection.selected(request)).as("and the stale choice is dropped").isEmpty();
    }

    @Test
    void namingABoundNamespaceUnderAnotherAddressIsRefused() throws Exception {
        namespaces.create("erni", "ERNI AI", UserId.of("david"));
        ScopeBindingFilter byHost = new ScopeBindingFilter(bound, namespaces, null, ERNI_HOST);
        signIn("david");
        MockHttpServletRequest request = under("app.example.com", "/api/agents");
        request.addHeader(ScopeBindingFilter.HEADER, "erni");
        MockHttpServletResponse response = new MockHttpServletResponse();

        byHost.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).contains("does not serve namespace 'erni'");
    }
}
