package ai.mindconnect.agent.runtime.capabilities.guard;

import ai.mindconnect.agent.runtime.api.hook.TurnAdmission;
import ai.mindconnect.agent.runtime.api.hook.TurnInput;

/**
 * Looks at what a user sends before any model sees it: personal data, an
 * attempt to override the instructions, a topic the agent must not touch.
 * Found through a {@link GuardResolver}; guards run in order and the first
 * refusal ends it.
 */
public interface InputGuard {

    /** Admits the input, possibly rewritten, or refuses it with a reason the caller is told. */
    TurnAdmission check(TurnInput input);
}
