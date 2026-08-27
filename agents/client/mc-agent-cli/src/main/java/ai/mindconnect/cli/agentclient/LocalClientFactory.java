package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentRegistryService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.springframework.stereotype.Component;

@Component
public class LocalClientFactory {

    private final AgentRegistryService registryService;
    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final LlmConfigRepository llmConfigRepository;

    public LocalClientFactory(AgentRegistryService registryService,
                               AgentSessionService sessionService,
                               AgentChatService chatService,
                               LlmConfigRepository llmConfigRepository) {
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.llmConfigRepository = llmConfigRepository;
    }

    public AgentClient createAgentClient() {
        return new LocalAgentClient(registryService, sessionService, chatService);
    }

    public LlmConfigRepository getLlmConfigRepository() {
        return llmConfigRepository;
    }
}
