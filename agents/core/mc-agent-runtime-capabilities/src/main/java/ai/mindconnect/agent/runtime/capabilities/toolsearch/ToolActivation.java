package ai.mindconnect.agent.runtime.capabilities.toolsearch;

import java.util.Collection;
import java.util.Set;

import ai.mindconnect.agent.SessionId;

/**
 * Which held-back tools a session has found. Kept per session and not per
 * turn: a tool found once stays offered, and with a durable store it
 * survives a restart.
 */
public interface ToolActivation {

    Set<String> activated(SessionId sessionId);

    void activate(SessionId sessionId, Collection<String> toolNames);
}
