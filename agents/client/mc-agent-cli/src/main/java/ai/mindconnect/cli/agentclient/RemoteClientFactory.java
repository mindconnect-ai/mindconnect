package ai.mindconnect.cli.agentclient;

import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the clients for remote mode. With {@code mindconnect.remote.token}
 * (or the environment variable {@code MC_REMOTE_TOKEN}) set, every request
 * carries it as a bearer token: the server takes the user from that, not from
 * anything the CLI puts in a request.
 */
@Component
public class RemoteClientFactory {

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final String remoteUrl;

    public RemoteClientFactory(OkHttpClient okHttpClient,
                               ObjectMapper objectMapper,
                               @Value("${mindconnect.remote.url:}") String remoteUrl,
                               @Value("${mindconnect.remote.token:${MC_REMOTE_TOKEN:}}") String remoteToken) {
        this.okHttpClient = withBearer(okHttpClient, remoteToken);
        this.objectMapper = objectMapper;
        this.remoteUrl = remoteUrl;
    }

    /**
     * The shared client, plus an {@code Authorization} header when there is a
     * token. A derived client, not the bean itself: the bean also serves the
     * LLM gateways of local mode, which must not see the token.
     */
    static OkHttpClient withBearer(OkHttpClient client, String token) {
        if (token == null || token.isBlank()) {
            return client;
        }
        String header = "Bearer " + token.trim();
        return client.newBuilder()
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .header("Authorization", header).build()))
                .build();
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
