package ai.mindconnect.agent.runtime.feature.subagents;

import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.runtime.service.task.AgentTurnWorker;
import ai.mindconnect.agent.runtime.service.task.SubAgentSupport;

import java.util.Set;

/**
 * Agents delegating to agents: an agent whose roster names others (or whose
 * project defines some) gets the inline {@code run_agent} and
 * {@code run_agents} tools, a delegation opens a sub-agent session that
 * can delegate again, up to {@link #maxDepth}. Without this feature a
 * roster is ignored and a delegation call answers with an error. Needs the
 * tools feature: delegation is a tool call.
 */
public class SubAgentsFeature extends ConfigurableFeature {

    private int maxDepth = AgentTurnWorker.MAX_DEPTH;

    /** The deepest chain of sub-agents allowed (default {@value AgentTurnWorker#MAX_DEPTH}). */
    public SubAgentsFeature maxDepth(int depth) {
        changing();
        this.maxDepth = depth;
        return this;
    }

    @Override
    public String name() {
        return "sub-agents";
    }

    @Override
    public Set<Class<? extends RuntimeFeature>> dependsOn() {
        return Set.of(ToolsFeature.class);
    }

    @Override
    protected void install(FeatureContext ctx) {
        ctx.instance(SubAgentSupport.class, SubAgentSupport.enabled(maxDepth));
    }
}
