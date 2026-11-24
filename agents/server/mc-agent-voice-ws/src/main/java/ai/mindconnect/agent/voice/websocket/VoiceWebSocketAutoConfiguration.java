package ai.mindconnect.agent.voice.websocket;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.feature.RuntimeView;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.voice.VoicePorts;
import ai.mindconnect.agent.voice.VoiceSettings;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.audio.adapter.openai.OpenAiSpeechGateway;
import ai.mindconnect.llm.audio.port.in.LlmSpeech;
import ai.mindconnect.llm.audio.service.RoutingLlmSpeechService;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the voice endpoint on a server that has the agent runtime. Adding
 * the module is all a host does: the endpoint ({@code mindconnect.voice.path},
 * default {@code /api/voice}) sits under the API's security, so a client
 * authenticates its handshake like any API call and names the namespace with
 * {@code X-Mindconnect-Namespace}. Speech output uses the runtime's HTTP
 * client, encryption and variables.
 */
@AutoConfiguration
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "mindconnect.voice", name = "enabled", matchIfMissing = true)
public class VoiceWebSocketAutoConfiguration implements WebSocketConfigurer {

    @Value("${mindconnect.voice.path:/api/voice}")
    private String path;

    @Value("${mindconnect.voice.allowed-origins:}")
    private String[] allowedOrigins;

    @Value("${mindconnect.voice.max-utterance-bytes:10485760}")
    private int maxUtteranceBytes;

    private final ObjectProvider<VoiceWebSocketHandler> handler;
    private final ObjectProvider<VoiceHandshakeInterceptor> interceptor;

    public VoiceWebSocketAutoConfiguration(ObjectProvider<VoiceWebSocketHandler> handler,
                                           ObjectProvider<VoiceHandshakeInterceptor> interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmSpeech llmSpeech(RuntimeView runtime, LlmConfigRepository configs, ObjectMapper json) {
        var gateway = new OpenAiSpeechGateway(runtime.beans().get(OkHttpClient.class), json,
                runtime.beans().get(EncryptionHelper.class), runtime.beans().get(EnvVarResolver.class));
        return new RoutingLlmSpeechService(configs, gateway);
    }

    @Bean
    public VoiceSettings voiceSettings(@Value("${mindconnect.voice.speech-to-text:speech-to-text}") String stt,
                                       @Value("${mindconnect.voice.text-to-speech:text-to-speech}") String tts,
                                       @Value("${mindconnect.voice.language:de}") String language) {
        return new VoiceSettings(stt, tts, language, maxUtteranceBytes);
    }

    @Bean
    public VoicePorts voicePorts(AgentDefinitionRepository agents, AgentSessionRepository sessions,
                                 AgentSessionService sessionService, AgentChatService chat,
                                 LlmTranscription transcription, LlmSpeech speech, LlmConfigRepository llmConfigs,
                                 ScopeSupplier scope, VoiceSettings settings) {
        return new VoicePorts(agents, sessions, sessionService, chat, transcription, speech, llmConfigs, scope,
                settings);
    }

    @Bean
    public VoiceWebSocketHandler voiceWebSocketHandler(VoicePorts ports, ObjectMapper json) {
        return new VoiceWebSocketHandler(ports, json);
    }

    @Bean
    public VoiceHandshakeInterceptor voiceHandshakeInterceptor(CurrentUsers currentUsers, ScopeSupplier scope) {
        return new VoiceHandshakeInterceptor(currentUsers, scope);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler.getObject(), path).addInterceptors(interceptor.getObject());
        if (allowedOrigins.length > 0) registration.setAllowedOriginPatterns(allowedOrigins);
    }
}
