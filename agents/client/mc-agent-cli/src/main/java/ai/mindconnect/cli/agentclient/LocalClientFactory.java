package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.springframework.stereotype.Component;

/** The clients of local mode: on the runtime the application built. */
@Component
public class LocalClientFactory {

    private final AgentRuntime runtime;
    private final LlmConfigRepository llmConfigRepository;

    public LocalClientFactory(AgentRuntime runtime, LlmConfigRepository llmConfigRepository) {
        this.runtime = runtime;
        this.llmConfigRepository = llmConfigRepository;
    }

    public AgentClient createAgentClient() {
        return new LocalAgentClient(runtime);
    }

    public LlmConfigRepository getLlmConfigRepository() {
        return llmConfigRepository;
    }
}
