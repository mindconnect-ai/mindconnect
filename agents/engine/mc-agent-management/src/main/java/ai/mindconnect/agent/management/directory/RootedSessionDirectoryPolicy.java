package ai.mindconnect.agent.management.directory;

import java.nio.file.Path;
import java.util.Optional;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.runtime.api.domain.UserId;

/**
 * An installation's say on session directories from its configuration: the
 * root its users may work under, whether they may choose at all, and the
 * directory a session has under its user's home.
 */
public final class RootedSessionDirectoryPolicy implements SessionDirectoryPolicy {

    private final WorkingDirPolicy policy;
    private final UserHome userHome;

    public RootedSessionDirectoryPolicy(WorkingDirPolicy policy, UserHome userHome) {
        this.policy = policy;
        this.userHome = userHome;
    }

    @Override
    public String admit(UserId user, String directory) {
        return policy.forUser(user.value()).validate(directory);
    }

    @Override
    public Optional<String> assign(UserId user, ai.mindconnect.agent.runtime.api.domain.SessionId session) {
        return userHome.sessionDirOf(user.value(),
                        session.value())
                .map(Path::toString);
    }

    @Override
    public Optional<String> ownDirectory(ManagedSession session) {
        return userHome.existingSessionDirOf(session.userId().value(),
                        session.id().value())
                .map(Path::toString);
    }
}
