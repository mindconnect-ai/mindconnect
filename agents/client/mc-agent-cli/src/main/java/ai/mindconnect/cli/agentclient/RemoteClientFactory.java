package ai.mindconnect.cli.agentclient;

import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RemoteClientFactory {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final String remoteUrl;

    public RemoteClientFactory(OkHttpClient okHttpClient,
                               ObjectMapper objectMapper,
                               @Value("${mindconnect.remote.url:}") String remoteUrl) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.remoteUrl = remoteUrl;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public boolean isConfigured() {
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    public AgentClient createAgentClient() {
        return new RemoteAgentClient(remoteUrl, okHttpClient, objectMapper);
    }

    public LlmConfigRepository createLlmConfigRepository() {
        return new RemoteLlmConfigRepository(remoteUrl, okHttpClient, objectMapper);
    }
}