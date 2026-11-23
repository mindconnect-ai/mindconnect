package ai.mindconnect.agent.runtime.capabilities.permission;

import java.util.List;

import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * Where rules come from: settings files in the home and working directory, a
 * user's configuration on a server. Contributed; sources are asked in the
 * order they were installed and the first rule that covers a tool decides.
 * A source that has layers of its own returns the narrower layer first.
 */
public interface PermissionRuleSource {

    List<PermissionRule> rules(Session session);
}
