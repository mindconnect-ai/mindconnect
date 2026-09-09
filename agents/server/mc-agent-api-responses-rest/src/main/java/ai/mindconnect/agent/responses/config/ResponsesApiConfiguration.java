package ai.mindconnect.agent.responses.config;

import ai.mindconnect.common.Namespace;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.protocol.runtime.AgentRuntimeBackend;
import ai.mindconnect.agent.responses.ModelResolver;
import ai.mindconnect.agent.responses.ResponsesMapper;
import ai.mindconnect.agent.responses.SessionBinder;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.message.port.in.ConversationManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Responses API onto whatever runtime the host application already
 * has. An app includes this module and imports this class; nothing else is
 * needed, and nothing here is specific to one host.
 */
@Configuration
@ComponentScan(basePackages = "ai.mindconnect.agent.responses.controller")
public class ResponsesApiConfiguration {

    /**
     * The agent that answers when {@code model} names an llm-config rather
     * than an agent — the model moves, the agent stays.
     */
    @Value("${mindconnect.responses.default-agent:default-chat}")
    private String defaultAgent;

    /**
     * Every request runs as this user until authentication is wired up.
     * Stated as a property rather than hidden in a constant, so an operator
     * can see what the API acts as before exposing it.
     */
    @Value("${mindconnect.responses.user-id:mc_user}")
    private String userId;

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntimeBackend responsesRuntimeBackend(AgentChatService chat,
                                                       AgentSessionService sessions,
                                                       AgentDefinitionRepository agents,
                                                       ConversationManager conversations) {
        return new AgentRuntimeBackend(chat, sessions, agents, conversations, userId);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelResolver responsesModelResolver(AgentDefinitionRepository agents,
                                                LlmConfigRepository llmConfigs,
                                                Namespace namespace) {
        return new ModelResolver(agents, llmConfigs, namespace, defaultAgent);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionBinder responsesSessionBinder(AgentRuntimeBackend backend,
                                                AgentSessionService sessions,
                                                AgentDefinitionRepository agents,
                                                Namespace namespace) {
        return new SessionBinder(backend, sessions, agents, namespace);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponsesMapper responsesMapper(ObjectMapper json) {
        return new ResponsesMapper(json);
    }
}
