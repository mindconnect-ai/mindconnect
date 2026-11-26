package ai.mindconnect.agent.runtime.api.port.out;

import java.util.List;

import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * The layers of model settings that lie below the request, the session and
 * the agent: a user's, a project's, the default of the installation. A
 * harness reads them from settings files, a server from its configuration.
 */
public interface ModelSettingsResolver {

    /** The layers for this session, strongest first; empty when there are none. */
    List<ModelSettings> layers(Session session);
}
