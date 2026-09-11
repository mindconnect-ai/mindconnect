package ai.mindconnect.agent.runtime.adapter.local;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.common.DomainException;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.domain.AgentSpec;
import ai.mindconnect.agent.runtime.port.in.AgentRegistry;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.AuthenticationInfo;

import java.util.List;
import java.util.Optional;

/**
 * In-process {@link AgentRegistry} bound to one {@code auth}.
 *
 * <p>Methods take no auth argument — it is bound at construction. Calls are
 * forwarded to {@link AgentRegistryService}.
 */
public class LocalAgentRegistry implements AgentRegistry {

    private final AgentRegistryService service;
    private final AuthenticationInfo auth;

    public LocalAgentRegistry(AgentRegistryService service,
                               AuthenticationInfo auth) {
        this.service = service;
        this.auth = auth;
    }

    @Override
    public AgentDefinition create(AgentSpec spec) {
        return service.create(spec);
    }

    @Override
    public AgentDefinition update(AgentId agentId, AgentPatch patch) {
        return service.update(agentId, patch);
    }

    @Override
    public Optional<AgentDefinition> find(AgentId agentId) {
        return service.find(agentId);
    }

    @Override
    public List<AgentDefinition> list() {
        return service.list();
    }

    @Override
    public void delete(AgentId agentId) {
        service.delete(agentId);
    }

}
