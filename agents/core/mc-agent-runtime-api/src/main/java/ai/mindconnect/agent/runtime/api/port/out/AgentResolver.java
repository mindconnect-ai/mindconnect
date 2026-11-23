package ai.mindconnect.agent.runtime.api.port.out;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * Where agent definitions come from. A harness reads them from files in the
 * session's working directory and the user's home; a server from its agent
 * repository, per tenant.
 */
public interface AgentResolver {

    Optional<AgentDefinition> resolve(String agentName, Session session);

    /**
     * Several resolvers as one: asked in order, the first that knows the name
     * wins. Takes a supplier because resolvers are contributed and the list
     * is not complete until every plugin is installed.
     */
    static AgentResolver firstOf(Supplier<List<AgentResolver>> resolvers) {
        return (agentName, session) -> resolvers.get().stream()
                .map(resolver -> resolver.resolve(agentName, session))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** A fixed set of agents, the same for every session. */
    static AgentResolver of(List<AgentDefinition> agents) {
        List<AgentDefinition> fixed = List.copyOf(agents);
        return (agentName, session) -> fixed.stream().filter(agent -> agent.name().equals(agentName)).findFirst();
    }
}
