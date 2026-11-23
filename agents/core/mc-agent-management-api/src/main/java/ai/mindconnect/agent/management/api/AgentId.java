package ai.mindconnect.agent.management.api;

/**
 * What a stored agent is known by for good. Its name is what the runtime
 * and other agents call it by and may be changed; the id is what a link, a
 * chat's choice of agent or a registry entry holds on to.
 */
public record AgentId(String value) {

    public static AgentId of(String value) {
        return new AgentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
