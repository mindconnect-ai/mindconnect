package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.List;

import ai.mindconnect.agent.loop.domain.RoundOutcome;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.port.out.AgentRoundAdvisor;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;

/**
 * Runs the answer hook on the round that ends a turn, before the loop
 * writes the round down — so what a guard replaces the answer with is what
 * the session keeps and the user reads.
 */
final class AnswerHookAdvisor implements AgentRoundAdvisor {

    private final HookRunner hooks;
    private final AgentContext context;

    AnswerHookAdvisor(HookRunner hooks, AgentContext context) {
        this.hooks = hooks;
        this.context = context;
    }

    @Override
    public RoundOutcome aroundRound(RoundContext round, Execution execution) {
        RoundOutcome outcome = execution.proceed();
        if (outcome.outcome() != RoundOutcome.Outcome.ANSWERED || outcome.added().isEmpty()) {
            return outcome;
        }
        List<TurnMessage> added = new ArrayList<>(outcome.added());
        int last = added.size() - 1;
        TurnMessage answer = added.get(last);
        added.set(last, hooks.run(RuntimeHook.ANSWER, context, asked -> answer));
        return new RoundOutcome(added, outcome.outcome(), outcome.usage(), outcome.waitingFor());
    }
}
