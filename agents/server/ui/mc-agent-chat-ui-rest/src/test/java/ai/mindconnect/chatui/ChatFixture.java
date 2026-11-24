package ai.mindconnect.chatui;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.UserId;

/** The agents and sessions the chat's components are drawn from in a test. */
public final class ChatFixture {

    private ChatFixture() {
    }

    /** A stored agent of that name, on that prompt and LLM configuration, without tools. */
    public static ManagedAgent agent(String name, String description, String prompt, String model) {
        return agent(name, description, prompt, model, List.of());
    }

    public static ManagedAgent agent(String name, String description, String prompt, String model,
                                     List<ToolBinding> tools) {
        Instant now = Instant.now();
        return new ManagedAgent(AgentId.of(UUID.randomUUID().toString()),
                new AgentDefinition(name, description, prompt,
                        model == null ? ModelSettings.NONE : ModelSettings.ofModel(model), tools, 5, Map.of()),
                null, null, null, ManagedAgent.Status.ACTIVE, now, now, 1);
    }

    /** A chat of {@code user} on {@code agent}, in those directories. */
    public static ManagedSession session(String user, ManagedAgent agent, String workingDirectory,
                                         List<String> additionalDirectories) {
        return new ManagedSession(SessionId.of(UUID.randomUUID().toString()), UserId.of(user), null,
                agent == null ? null : new SessionAgent.Ref(agent.id()), null, ManagedSession.CHAT,
                ManagedSession.Status.ACTIVE, new SessionDirectories(workingDirectory, additionalDirectories),
                Instant.now(), agent == null ? Map.of() : Map.of(ManagedSession.AGENT_ID, agent.id().value()));
    }
}
