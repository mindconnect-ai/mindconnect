package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.SessionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Releasing a session is how whoever ends it reaches what the providers hold
 * for it — the registry is the one object that knows every provider.
 */
class SpiToolRegistryReleaseTest {

    private static final ToolEnvironment EMPTY = new ToolEnvironment() {
        @Override public <T> Optional<T> get(Class<T> type) { return Optional.empty(); }
        @Override public Optional<String> getString(String key) { return Optional.empty(); }
    };

    private SpiToolRegistry registry;

    @BeforeEach
    void setUp() {
        WarmUpProviders.reset();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.close();
    }

    @Test
    void releasing_a_session_reaches_the_providers() {
        registry = new SpiToolRegistry(EMPTY, getClass().getClassLoader(), new long[0]);
        SessionId session = SessionId.random();

        registry.releaseSession(session);

        assertThat(WarmUpProviders.fastReleases).containsExactly(session);
    }
}
