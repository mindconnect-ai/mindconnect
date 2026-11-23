package ai.mindconnect.agent.starter.runtime.bridge;

import java.nio.file.Path;
import java.util.Optional;

import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.service.UserHome;
import ai.mindconnect.agent.runtime.service.WorkingDirPolicy;

/**
 * The server's say on session directories, from what the earlier runtime is
 * configured with: the root its users may work under, whether they may
 * choose at all, and the directory a session has under its user's home.
 */
final class WorkingDirSessionDirectoryPolicy implements SessionDirectoryPolicy {

    private final WorkingDirPolicy policy;
    private final UserHome userHome;

    WorkingDirSessionDirectoryPolicy(WorkingDirPolicy policy, UserHome userHome) {
        this.policy = policy;
        this.userHome = userHome;
    }

    @Override
    public String admit(UserId user, String directory) {
        return policy.forUser(user.value()).validate(directory);
    }

    @Override
    public Optional<String> ownDirectory(ManagedSession session) {
        return userHome.existingSessionDirOf(ai.mindconnect.agent.UserId.of(session.userId().value()),
                        ai.mindconnect.agent.SessionId.of(session.id().value()))
                .map(Path::toString);
    }
}
