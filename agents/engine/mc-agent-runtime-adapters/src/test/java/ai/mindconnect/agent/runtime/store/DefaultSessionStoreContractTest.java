package ai.mindconnect.agent.runtime.store;

import java.time.Clock;

import org.junit.jupiter.api.Nested;

import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;

/** The store that keeps a session's messages in a repository of their own, over each kind of repository. */
class DefaultSessionStoreContractTest {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("test"));

    @Nested
    class InMemory extends SessionStoreContract {

        @Override
        protected SessionStore store() {
            return new DefaultSessionStore(new InMemorySessionRepository(SCOPE),
                    new InMemorySessionMessageRepository(SCOPE, Clock.systemUTC()));
        }
    }
}
