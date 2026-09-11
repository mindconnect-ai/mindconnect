package ai.mindconnect.agentrest.auth;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The caller comes from the host's resolver; without one there is a dev user
 * only while authentication is off. Sessions open only for their owner.
 */
class CurrentUsersTest {

    private static CurrentUsers currentUsers(CurrentUserResolver resolver, boolean authEnabled) {
        var beans = new StaticListableBeanFactory();
        if (resolver != null) {
            beans.addBean("resolver", resolver);
        }
        return new CurrentUsers(beans.getBeanProvider(CurrentUserResolver.class), authEnabled, "dev");
    }

    @Test
    void theHostsResolverDecides() {
        assertThat(currentUsers(() -> Optional.of(UserId.of("alice")), true).current())
                .contains(UserId.of("alice"));
        assertThat(currentUsers(Optional::empty, false).current()).isEmpty();
    }

    @Test
    void withoutAResolverTheDevUserExistsOnlyWhileAuthenticationIsOff() {
        assertThat(currentUsers(null, false).require()).isEqualTo(UserId.of("dev"));

        CurrentUsers closed = currentUsers(null, true);
        assertThat(closed.current()).isEmpty();
        assertThatThrownBy(closed::require)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void aSessionOpensOnlyForItsOwner() {
        var repo = new InMemoryAgentSessionRepository();
        AgentSession alices = repo.create(AgentSession.start(AgentId.of("default-chat"), UserId.of("alice"),
                ConversationId.random()));
        var access = new SessionAccess(repo);

        assertThat(access.requireOwned(alices.id(), UserId.of("alice"))).isEqualTo(alices);
        assertThat(access.owned(alices.id(), UserId.of("bob"))).isEmpty();
        assertThatThrownBy(() -> access.requireOwned(alices.id(), UserId.of("bob")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(access.owned(SessionId.random(), UserId.of("alice"))).isEmpty();
    }
}
