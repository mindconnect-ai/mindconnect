package ai.mindconnect.agent.runtime.infrastructure;

import ai.mindconnect.agent.chat.stream.SessionChannels;
import ai.mindconnect.agent.chat.stream.UserChannels;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * What a user's open pages hear of the runtime: a turn that started, ended
 * or waits for an answer, in whichever of the user's sessions it runs.
 */
public final class UserStreamPlugin implements Plugin {

    @Override
    public String name() {
        return "user-stream";
    }

    @Override
    public void install(PluginRegistry registry) {
        UserChannels users = new UserChannels();
        registry.provide(UserChannels.class, () -> users);
        registry.provide(SessionChannels.class, SessionChannels::new);
        registry.contribute(SessionEventListener.class, new UserEventFeed(users,
                registry.supplier(TurnService.class), registry.supplier(SessionStore.class)));
    }
}
