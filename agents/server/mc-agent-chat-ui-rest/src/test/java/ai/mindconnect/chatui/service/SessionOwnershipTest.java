package ai.mindconnect.chatui.service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session id opens a chat only for the user who started it. Anyone else
 * — another user, or a channel that is not a chat's at all — gets an empty
 * answer, indistinguishable from "no such session".
 */
class SessionOwnershipTest {

    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private final SessionOwnership ownership = new SessionOwnership(sessions);

    private final AgentSession alices = sessions.create(
            AgentSession.start(AgentId.of("default-chat"), UserId.of("alice"), ConversationId.random()));

    @Test
    void theOwnerReachesTheSessionAndNobodyElseDoes() {
        assertThat(ownership.owned(alices.id(), user("alice"))).contains(alices);
        assertThat(ownership.owned(alices.id(), user("bob"))).isEmpty();
        assertThat(ownership.owned(SessionId.random(), user("alice"))).isEmpty();
    }

    @Test
    void withoutAPrincipalTheRequestRunsAsTheDevUser() {
        var devs = sessions.create(AgentSession.start(AgentId.of("default-chat"),
                UserId.of(SessionOwnership.ANONYMOUS_USER), ConversationId.random()));
        assertThat(SessionOwnership.userIdOf(null)).isEqualTo(SessionOwnership.ANONYMOUS_USER);
        assertThat(ownership.owned(devs.id(), null)).contains(devs);
        assertThat(ownership.owned(alices.id(), null)).isEmpty();
    }

    @Test
    void aChannelIsOwnedExactlyWhenItsSessionIs() {
        String channel = SessionOwnership.channelOf(alices.id());
        assertThat(SessionOwnership.sessionIdOf(channel)).contains(alices.id());
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
