package ai.mindconnect.agent.runtime.capabilities.guard;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;

/**
 * Looks at an agent's final answer before the user does: a reviewer, a
 * check for advice the agent must not give, a required disclaimer.
 * Found through a {@link GuardResolver}; each guard gets what the one before
 * it returned.
 */
public interface OutputGuard {

    /** The answer as it should go out: unchanged, amended or replaced. */
    TurnMessage check(AgentContext context, TurnMessage answer);
}
