package ai.mindconnect.agent.sdk.inmemory;

import java.util.List;

import ai.mindconnect.agent.runtime.capabilities.guard.GuardResolver;
import ai.mindconnect.agent.runtime.capabilities.guard.InputGuard;
import ai.mindconnect.agent.runtime.capabilities.guard.OutputGuard;
import ai.mindconnect.agent.runtime.api.domain.Session;

/** Guards built in code and handed over as lists, the same for every session. */
public final class InMemoryGuardResolver implements GuardResolver {

    private final List<InputGuard> inputGuards;
    private final List<OutputGuard> outputGuards;

    public InMemoryGuardResolver(List<InputGuard> inputGuards, List<OutputGuard> outputGuards) {
        this.inputGuards = List.copyOf(inputGuards);
        this.outputGuards = List.copyOf(outputGuards);
    }

    @Override
    public List<InputGuard> inputGuards(Session session) {
        return inputGuards;
    }

    @Override
    public List<OutputGuard> outputGuards(Session session) {
        return outputGuards;
    }
}
