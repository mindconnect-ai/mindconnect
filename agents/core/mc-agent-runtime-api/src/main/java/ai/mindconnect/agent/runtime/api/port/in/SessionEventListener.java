package ai.mindconnect.agent.runtime.api.port.in;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;

/**
 * Where session events go. Called in the order things happen within one
 * session. An implementation that throws loses its event and costs the
 * session nothing.
 *
 * <p>A caller subscribes one with {@link SessionService#subscribe} for the
 * sessions it follows. A plugin contributes one to hear every session.
 */
@FunctionalInterface
public interface SessionEventListener {

    void onEvent(SessionEvent event);
}
