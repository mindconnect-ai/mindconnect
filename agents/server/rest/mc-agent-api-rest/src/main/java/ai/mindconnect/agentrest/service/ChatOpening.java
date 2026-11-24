package ai.mindconnect.agentrest.service;

import java.util.List;

import org.springframework.stereotype.Service;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.infrastructure.UserChannels;
import ai.mindconnect.agent.runtime.infrastructure.UserEvent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.sdk.AgentRuntime;

/**
 * Opens a chat on a stored agent and tells the user's other pages about it:
 * a chat started in one window shows in the list of the next.
 */
@Service
public class ChatOpening {

    private final AgentRuntime runtime;
    private final UserChannels users;

    public ChatOpening(AgentRuntime runtime, UserChannels users) {
        this.runtime = runtime;
        this.users = users;
    }

    /**
     * @param type             what kind of session it is; {@code null} for a chat
     * @param workingDirectory {@code null} for the directory the installation gives the chat
     * @throws IllegalArgumentException for an agent that does not exist or a directory the user may not have
     */
    public ManagedSession open(AgentId agent, UserId user, String type, String workingDirectory,
                               List<String> additionalDirectories) {
        ManagedSession session = runtime.service(SessionManagement.class).open(
                user, null,
                new SessionAgent.Ref(agent), type,
                new SessionDirectories(workingDirectory,
                        additionalDirectories == null ? List.of() : additionalDirectories));
        users.publish(user, new UserEvent.SessionStarted(session.id(), agent, session.type()));
        return session;
    }
}
