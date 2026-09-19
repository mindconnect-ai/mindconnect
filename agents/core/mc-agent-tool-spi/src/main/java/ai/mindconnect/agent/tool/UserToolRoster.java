package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;

import java.util.List;

/**
 * The tools a user keeps in their own account, laid over what an agent lists.
 *
 * <p>The chain of who may say what about a tool ran Source → Installation →
 * Namespace → Agent and stopped there. This is the step below it: the person.
 * Two things they can do, and one record does both — a binding for a tool the
 * agent does not list <em>adds</em> it, a binding for one it does
 * <em>overrides</em> it.
 *
 * <h2>Where it applies, and where it deliberately does not</h2>
 * Only to the agent a user is chatting with. A sub-agent keeps the roster its
 * definition gives it: somebody curated that list for a narrow job, and a
 * research helper that silently gained the ability to send mail would be a
 * surprise nobody asked for.
 *
 * <p>The <em>connections</em> are a different matter and follow the user
 * everywhere: a sub-agent whose own definition has a mail tool runs it on that
 * user's mailbox, because {@link ToolCallScope#userId()} is the same all the
 * way down the chain.
 *
 * <h2>What a user may not do</h2>
 * Switch on what the installation switched off — that is not enforced here but
 * below, by the registry: a disabled tool does not resolve, whoever asks for
 * it. And loosen an approval: {@code needsApproval} may be tightened by any
 * layer and relaxed by none.
 */
public interface UserToolRoster {

    /**
     * {@code agentRefs} with this user's own applied: their additions
     * appended, their overrides laid over, the ones they switched off removed.
     *
     * @param userId    whose account; null for work on nobody's behalf — then
     *                  {@code agentRefs} comes back untouched
     * @param agentId   the agent being resolved; a user binding may name one
     *                  agent or all of them
     * @param agentRefs what the agent lists, already including what
     *                  {@code tool_search} activated
     */
    List<AgentTool> apply(UserId userId, AgentId agentId, List<AgentTool> agentRefs);

    /** Nobody keeps tools of their own — the behaviour before this layer existed. */
    static UserToolRoster none() {
        return (userId, agentId, agentRefs) -> agentRefs;
    }
}
