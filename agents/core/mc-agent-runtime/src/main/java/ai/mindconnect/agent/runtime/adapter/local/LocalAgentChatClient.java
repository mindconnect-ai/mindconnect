package ai.mindconnect.agent.runtime.adapter.local;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.in.AgentChatClient;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * In-process {@link AgentChatClient} bound to one chat session.
 *
 * <p>Holds the {@link AgentSession} and {@link AgentDefinition} as a
 * snapshot taken when the client was constructed; methods that mutate either
 * (e.g. title generation) do not refresh this snapshot. Re-attach via
 * {@code AgentRuntime.attachChat} to get a fresh view.
 *
 * <p>The client is a cheap value-like handle: it delegates everything to
 * {@link AgentChatService} and {@link AgentSessionService}.
 */
public class LocalAgentChatClient implements AgentChatClient {

    private final AgentChatService chatService;
    private final AgentSessionService sessionService;
    private final AgentSession session;
    private final AgentDefinition definition;

    public LocalAgentChatClient(AgentChatService chatService,
                                 AgentSessionService sessionService,
                                 AgentSession session,
                                 AgentDefinition definition) {
        this.chatService = chatService;
        this.sessionService = sessionService;
        this.session = session;
        this.definition = definition;
    }

    @Override
    public UUID sessionId() { return session.id(); }

    @Override
    public AgentDefinition definition() { return definition; }

    @Override
    public ChatTurnHandle send(String userMessage, Consumer<StreamEvent> events) {
        return chatService.submitChat(session.id(), userMessage, events);
    }

    @Override
    public List<Message> history() {
        return sessionService.loadHistory(session.id());
    }

    @Override
    public int deleteMessages(int fromSeq, int toSeq) {
        return sessionService.deleteMessages(session.id(), fromSeq, toSeq);
    }

    @Override
    public WorkingMemory memorySnapshot() {
        return chatService.memorySnapshot(session.id());
    }

    @Override
    public int compressMemory() {
        return chatService.compressMemory(session.id());
    }
}
