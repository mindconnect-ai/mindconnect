package ai.mindconnect.mcp.gateway;

import ai.mindconnect.agent.EntityId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which registered MCP server — in the shape of every other id: a value and
 * nothing else. The namespace sits with the store that holds the
 * registration; one process serves one.
 *
 * <p>The value is checked here rather than where it is stored, because every
 * way to a registration passes through this record — the form, the API, a
 * seeded or hand-dropped file. It has to be a safe file name and a stable key;
 * lower case only, because a developer's file system treats {@code Foo} and
 * {@code foo} as one file and Postgres does not.
 */
public record McpServerId(@JsonValue String value) implements EntityId {

    public McpServerId {
        EntityId.check(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static McpServerId of(String value) {
        return new McpServerId(value);
    }

    /** A fresh, unique id. */
    public static McpServerId random() {
        return new McpServerId(EntityId.randomValue());
    }

    @Override
    public String toString() {
        return value;
    }
}
