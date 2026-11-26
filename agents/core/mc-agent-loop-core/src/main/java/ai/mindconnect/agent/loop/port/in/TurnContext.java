package ai.mindconnect.agent.loop.port.in;

import java.util.List;

import ai.mindconnect.agent.loop.port.out.AgentRoundAdvisor;
import ai.mindconnect.agent.loop.port.out.SteeringInbox;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.loop.port.out.TurnJournal;

/**
 * Everything that belongs to ONE turn and not to the loop: who it is, where
 * it is written, who watches, who may interrupt, which policy surrounds its
 * rounds. The caller builds the same context for the start of a turn and for
 * every resume — which is what lets one loop serve every turn and keep
 * nothing of any.
 *
 * @param requestId identifies the turn, from its start through every resume.
 *                  The caller chooses it: it must be known before the turn
 *                  runs, to cancel it and to tell a repeated start from a
 *                  new one. Every message the turn writes carries it as its
 *                  {@code turnId}
 * @param sessionId tells the model and the tools who they work for
 * @param events    where the turn's events go; {@code event -> { }} when
 *                  nobody watches
 * @param steering  what the user says while the turn runs; {@code List::of}
 *                  when the turn cannot be steered
 * @param advisors  turn-level policy around each round (reviewer chain,
 *                  tool-result compression) — in list order, first advisor
 *                  outermost
 */
public record TurnContext(
        String requestId,
        String sessionId,
        TurnJournal journal,
        TurnEventListener events,
        SteeringInbox steering,
        List<AgentRoundAdvisor> advisors
) {

    public TurnContext {
        advisors = List.copyOf(advisors);
    }
}
