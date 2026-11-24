package ai.mindconnect.agent.management.api;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a stored agent is known by for good. Its name is what the runtime
 * and other agents call it by and may be changed; the id is what a link, a
 * chat's choice of agent or a registry entry holds on to.
 */
public record AgentId(@JsonValue String value) {

    public static AgentId of(String value) {
        return new AgentId(value);
    }

    /**
     * In JSON an id is its plain value. Agents stored before that carry it
     * as {@code {"value": "…"}}, which is still read.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static AgentId fromJson(Object json) {
        return new AgentId(json instanceof Map<?, ?> wrapped ? String.valueOf(wrapped.get("value"))
                : String.valueOf(json));
    }

    @Override
    public String toString() {
        return value;
    }
}
