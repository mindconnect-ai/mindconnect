package ai.mindconnect.agentrest.config;

import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agentrest.stream.SessionFrameChannels;
import ai.mindconnect.agentrest.stream.SessionFrameFeed;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The stream of a session a client reconnects to, and what feeds it from the runtime. */
@Configuration
public class SessionFrameConfig {

    @Bean
    @ConditionalOnMissingBean
    SessionFrameChannels sessionFrameChannels() {
        return SessionFrameChannels.standalone();
    }

    @Bean
    Plugin sessionFrameFeedPlugin(SessionFrameChannels sessionFrames) {
        return new Plugin() {
            @Override
            public String name() {
                return "session-frame-feed";
            }

            @Override
            public void install(PluginRegistry registry) {
                registry.contribute(SessionEventListener.class,
                        new SessionFrameFeed(sessionFrames, registry.supplier(TurnService.class)));
            }
        };
    }
}
