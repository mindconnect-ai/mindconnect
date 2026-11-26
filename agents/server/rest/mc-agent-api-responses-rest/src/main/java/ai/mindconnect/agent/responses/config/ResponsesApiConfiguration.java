package ai.mindconnect.agent.responses.config;

import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.protocol.runtime.AgentRuntimeBackend;
import ai.mindconnect.agent.responses.ModelResolver;
import ai.mindconnect.agent.responses.ResponsesMapper;
import ai.mindconnect.agent.responses.SessionBinder;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
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
     * The backend, acting for the authenticated caller of each request
     * ({@link CurrentUsers}): sessions, responses and files are the caller's
     * own. With file support when the host has a file store and the chat's
     * attach service: an {@code input_file} or {@code input_image} part lands
     * in the store and on the session the way a chat upload does — a document
     * is ingested for {@code vector_search}, an image or PDF goes to the model
     * with the message.
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentRuntimeBackend responsesRuntimeBackend(AgentRuntime runtime, CurrentUsers currentUsers,
                                                       org.springframework.beans.factory.ObjectProvider<ai.mindconnect.agentrest.service.SessionFileService> sessionFiles) {
        AgentRuntimeBackend backend = AgentRuntimeBackend.on(runtime, currentUsers::require);
        var store = runtime.find(ai.mindconnect.filestore.FileStore.class);
        var attach = sessionFiles.getIfAvailable();
        if (store.isEmpty() || attach == null) {
            return backend;
        }
        return backend.withFiles(store.get(), (sessionId, stored) -> {
            var result = attach.attach(sessionId, stored);
            if (!result.success()) {
                throw new IllegalArgumentException(result.message());
            }
            return result.message();
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelResolver responsesModelResolver(AgentRuntime runtime) {
        return new ModelResolver(runtime.service(AgentManagement.class),
                runtime.service(LlmConfigRepository.class), defaultAgent);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionBinder responsesSessionBinder(AgentRuntimeBackend backend, AgentRuntime runtime) {
        return new SessionBinder(backend, runtime.service(SessionManagement.class),
                runtime.service(AgentManagement.class));
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponsesMapper responsesMapper(ObjectMapper json) {
        return new ResponsesMapper(json);
    }
}
