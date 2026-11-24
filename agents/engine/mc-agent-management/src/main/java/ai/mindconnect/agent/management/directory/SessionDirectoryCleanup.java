package ai.mindconnect.agent.management.directory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionCleanup;

/**
 * Removes a deleted session's own directory under its user's home — its
 * uploads, notes and logs, which on a server would pile up. Only that
 * directory: one the user chose is theirs and is never touched. Links
 * inside are removed, not followed.
 *
 * <p>Not installed by any plugin: deleting a chat must not take files
 * with it unless the host decided so. A server that wants it contributes
 * one as a {@link SessionCleanup}; the Spring starter does on
 * {@code mindconnect.sessions.delete-directory=true}.
 */
public final class SessionDirectoryCleanup implements SessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(SessionDirectoryCleanup.class);

    private final UserHome userHome;

    public SessionDirectoryCleanup(UserHome userHome) {
        this.userHome = userHome;
    }

    @Override
    public void deleted(ManagedSession session) {
        userHome.existingSessionDirOf(session.userId().value(),
                session.id().value()).ifPresent(directory -> {
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
