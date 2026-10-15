package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

import java.time.Clock;

public final class CurrentDateTimeToolFactory implements ToolFactory {

    /** Whose local time: the host's resolver, the JVM's zone without one. */
    private TimeZones zones = TimeZones.system();

    @Override public String name() { return "get_current_datetime"; }

    @Override public String group() { return "utilities"; }

    @Override public void bind(ToolEnvironment env) { this.zones = TimeZones.of(env); }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        TimeZones resolver = zones;
        return new CurrentDateTimeTool(Clock.systemUTC(),
                () -> resolver.zoneOf(scope == null ? null : scope.userId()));
    }
}
