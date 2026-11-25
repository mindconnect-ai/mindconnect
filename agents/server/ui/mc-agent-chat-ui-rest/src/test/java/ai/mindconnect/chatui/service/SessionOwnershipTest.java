package ai.mindconnect.chatui.service;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.management.RepositorySessionManagement;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.store.DefaultSessionStore;
import ai.mindconnect.agent.runtime.store.InMemorySessionMessageRepository;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session id opens a chat only for the user who started it. Anyone else
 * — another user, or a channel that is not a chat's at all — gets an empty
 * answer, indistinguishable from "no such session".
 */
class SessionOwnershipTest {

    private final InMemorySessionRepository stored = new InMemorySessionRepository(ScopeSupplier.fixed(new Namespace("test")));
    private final InMemorySessionMessageRepository messages = new InMemorySessionMessageRepository(ScopeSupplier.fixed(new Namespace("test")), java.time.Clock.systemUTC());
    private final SessionManagement management = new RepositorySessionManagement(stored, new DefaultSessionStore(stored, messages),
            new InMemoryAgentRepository(ScopeSupplier.fixed(new Namespace("test"))), SessionDirectoryPolicy.unrestricted(),
            List.of(),
                new ai.mindconnect.agent.management.SessionLifecycle(stored, session -> java.util.Optional.empty()));
    private final SessionOwnership ownership = new SessionOwnership(runtimeOver(management));

    private final ManagedSession alices = opened("alice");

    private ManagedSession opened(String user) {
        var id = SessionId.of(java.util.UUID.randomUUID().toString());
        new DefaultSessionStore(stored, messages).create(new Session(id,
                ai.mindconnect.agent.UserId.of(user), null,
                new AgentDefinition("helper", "Helps.", "You help.", ModelSettings.NONE, List.of(), 5, Map.of()),
                Map.of(), Instant.parse("2026-11-01T00:00:00Z")));
        return management.find(id).orElseThrow();
    }

    private static AgentRuntime runtimeOver(SessionManagement management) {
        PluginRegistry registry = new DefaultPluginRegistry();
        registry.provide(SessionManagement.class, () -> management);
        registry.start();
        return new AgentRuntime(registry, () -> { });
    }

    private static SessionId id(ManagedSession session) {
        return SessionOwnership.idOf(session);
    }

    @Test
    void theOwnerReachesTheSessionAndNobodyElseDoes() {
        assertThat(ownership.owned(id(alices), user("alice"))).contains(alices);
        assertThat(ownership.owned(id(alices), user("bob"))).isEmpty();
        assertThat(ownership.owned(SessionId.random(), user("alice"))).isEmpty();
    }

    @Test
    void withoutAPrincipalTheRequestRunsAsTheDevUser() {
        var devs = opened(SessionOwnership.ANONYMOUS_USER);
        assertThat(SessionOwnership.userIdOf(null)).isEqualTo(SessionOwnership.ANONYMOUS_USER);
        assertThat(ownership.owned(id(devs), null)).contains(devs);
        assertThat(ownership.owned(id(alices), null)).isEmpty();
    }

    @Test
    void aChannelIsOwnedExactlyWhenItsSessionIs() {
        String channel = SessionOwnership.channelOf(id(alices));
        assertThat(SessionOwnership.sessionIdOf(channel)).contains(id(alices));
        assertThat(ownership.ownedByChannel(channel, user("alice"))).contains(alices);
        assertThat(ownership.ownedByChannel(channel, user("bob"))).isEmpty();

        // Channels that are not a chat's: no session, so nobody owns them.
        assertThat(SessionOwnership.sessionIdOf("msg-list-Not A Session")).isEmpty();
        assertThat(SessionOwnership.sessionIdOf("user-stream")).isEmpty();
        assertThat(SessionOwnership.sessionIdOf(null)).isEmpty();
        assertThat(ownership.ownedByChannel("user-stream", user("alice"))).isEmpty();
    }

    private static OidcUser user(String name) {
        var token = OidcIdToken.withTokenValue("t")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject(name)
                .claim(StandardClaimNames.PREFERRED_USERNAME, name)
                .build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), token,
                StandardClaimNames.PREFERRED_USERNAME);
    }
}
