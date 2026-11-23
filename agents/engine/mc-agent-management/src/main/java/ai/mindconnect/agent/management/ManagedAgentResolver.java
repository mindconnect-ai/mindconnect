package ai.mindconnect.agent.management;

import java.util.Optional;

import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;

/**
 * The managed agents as the runtime finds them by name: how an agent that
 * names another as a sub-agent gets it. Only an active one — a draft is not
 * called by anybody, and neither is one that was put out of use.
 */
public final class ManagedAgentResolver implements AgentResolver {

    private final AgentManagement agents;

    public ManagedAgentResolver(AgentManagement agents) {
        this.agents = agents;
    }

    @Override
    public Optional<AgentDefinition> resolve(String agentName, Session session) {
        return agents.findByName(agentName)
                .filter(agent -> agent.status() == ManagedAgent.Status.ACTIVE)
                .map(ManagedAgent::definition);
    }
}
