package ai.mindconnect.agent.runtime.adapter.local;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.domain.AgentSpec;
import ai.mindconnect.agent.runtime.port.in.AgentRegistry;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.Namespace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * In-process {@link AgentRegistry} bound to one {@code (auth, namespace)}.
 *
 * <p>Methods take no auth or namespace argument — both are bound at
 * construction. Calls are forwarded to {@link AgentRegistryService}, which
 * enforces tenant isolation by checking that any agent loaded by id belongs
 * to the bound namespace.
 */
public class LocalAgentRegistry implements AgentRegistry {

    private final AgentRegistryService service;
    private final AuthenticationInfo auth;
    private final Namespace namespace;

    public LocalAgentRegistry(AgentRegistryService service,
                               AuthenticationInfo auth,
                               Namespace namespace) {
        this.service = service;
        this.auth = auth;
        this.namespace = namespace;
    }

    @Override
    public AgentDefinition create(AgentSpec spec) {
        return service.create(namespace, spec);
    }

    @Override
    public AgentDefinition update(UUID agentId, AgentPatch patch) {
        return service.update(namespace, agentId, patch);
    }

    @Override
    public Optional<AgentDefinition> find(UUID agentId) {
        return service.find(namespace, agentId);
    }

    @Override
    public List<AgentDefinition> list() {
        return service.list(namespace);
    }

    @Override
    public void delete(UUID agentId) {
        service.delete(namespace, agentId);
    }
}
