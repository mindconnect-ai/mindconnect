package ai.mindconnect.agent.runtime.capabilities.title;

import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.BackgroundWork;
import ai.mindconnect.agent.runtime.api.port.out.BackgroundWorker;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.SessionEventPublisher;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Session titles: once a session's first turn is through, a title is found
 * for it in the background, so that a list of sessions reads as more than
 * ids. The user does not wait for it.
 */
public final class SessionTitlePlugin implements Plugin {

    /** The key in a session's metadata that holds its title. A session that has one is given no other. */
    public static final String TITLE = SessionTitleWorker.TITLE;

    @Override
    public String name() {
        return "session-title";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.contribute(BackgroundWorker.class, new SessionTitleWorker(
                registry.supplier(SessionStore.class), registry.supplier(ModelGateway.class),
                registry.supplier(SessionEventPublisher.class)));

        registry.listen(RuntimeHook.TURN_END, (context, turn) -> {
            // A child session is a sub-agent's workspace; nobody lists it.
            if (turn.status() == TurnStatus.COMPLETED
                    && context.session().parentId() == null
                    && !context.session().metadata().containsKey(SessionTitleWorker.TITLE)) {
                registry.get(BackgroundWork.class).submit(SessionTitleWorker.NAME,
                        Map.of(SessionTitleWorker.SESSION_ID, context.session().id().value()));
            }
        });
    }
}
