package ai.mindconnect.agent.runtime.store;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.jackson.IdJsonModule;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jackson.JsonMapperFactory;

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

    @Nested
    class OnFiles extends SessionStoreContract {

        @TempDir
        Path directory;

        @Override
        protected SessionStore store() {
            ObjectMapper json = new JsonMapperFactory(List.of(new IdJsonModule())).create()
                    .registerModule(new JavaTimeModule());
            return new DefaultSessionStore(new FileSessionRepository(directory, json, SCOPE),
                    new FileSessionMessageRepository(directory, json, SCOPE, Clock.systemUTC()));
        }
    }
}
