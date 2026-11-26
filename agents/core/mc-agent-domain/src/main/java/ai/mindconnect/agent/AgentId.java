package ai.mindconnect.agent;

/**
 * Identifies an agent definition. See {@link EntityId}.
 */
public record AgentId(String value) implements EntityId {

    public AgentId {
        EntityId.check(value);
    }

    public static AgentId of(String value) {
        return new AgentId(value);
    }

    /** A fresh, unique id. */
    public static AgentId random() {
        return new AgentId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
