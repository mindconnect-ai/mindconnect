package ai.mindconnect.agent.runtime.store;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.jackson.IdJsonModule;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.message.adapter.file.FileMessageRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;

/**
 * The store that keeps a session's messages as a conversation of the message
 * repository, over both kinds of repository.
 *
 * <p>It gives every message an id of the message repository's, also one that
 * came with an id: an appended message with an id does not keep it.
 */
class RepositorySessionStoreContractTest {

    private static final ScopeSupplier SCOPE = ScopeSupplier.fixed(new Namespace("test"));
    private static final JsonMapperFactory MAPPERS = new JsonMapperFactory(List.of(new IdJsonModule()));

    @Nested
    class InMemory extends SessionStoreContract {

        @Override
        protected SessionStore store() {
            return new RepositorySessionStore(new InMemorySessionRepository(SCOPE), new InMemoryMessageRepository(SCOPE),
                    MAPPERS);
        }

        @Override
        protected boolean keepsAGivenId() {
            return false;
        }
    }

    @Nested
    class OnFiles extends SessionStoreContract {

        @TempDir
        Path directory;

        @Override
        protected SessionStore store() {
            ObjectMapper json = MAPPERS.create().registerModule(new JavaTimeModule());
            return new RepositorySessionStore(new FileSessionRepository(directory, json, SCOPE),
                    new FileMessageRepository(directory, json, SCOPE), MAPPERS);
        }

        @Override
        protected boolean keepsAGivenId() {
            return false;
        }
    }
}
