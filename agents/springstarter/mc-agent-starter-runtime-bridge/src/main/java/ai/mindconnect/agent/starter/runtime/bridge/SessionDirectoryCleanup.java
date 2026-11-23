package ai.mindconnect.agent.starter.runtime.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionCleanup;
import ai.mindconnect.agent.runtime.service.UserHome;

/**
 * A session's own directory under its user's home goes with it — its
 * uploads, notes and logs are nobody else's, and on a server they would
 * pile up. Only that directory: one the user chose is theirs and is never
 * touched. Links inside are removed, not followed.
 */
final class SessionDirectoryCleanup implements SessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(SessionDirectoryCleanup.class);

    private final UserHome userHome;

    SessionDirectoryCleanup(UserHome userHome) {
        this.userHome = userHome;
    }

    @Override
    public void deleted(ManagedSession session) {
        userHome.existingSessionDirOf(ai.mindconnect.agent.UserId.of(session.userId().value()),
                ai.mindconnect.agent.SessionId.of(session.id().value())).ifPresent(directory -> {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException | UncheckedIOException e) {
                log.warn("The directory of deleted session {} could not be removed entirely: {}",
                        session.id().value(), e.getMessage());
            }
        });
    }
}
