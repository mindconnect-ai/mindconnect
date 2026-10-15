package ai.mindconnect.office.tools;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.calendar.CalendarAccounts;

import java.util.Optional;
import java.util.Set;

/**
 * The calendar tools: one set for every calendar account a user connected,
 * whatever kind it is — {@code calendar_calendars}, {@code calendar_events},
 * {@code calendar_read}, {@code calendar_create}, {@code calendar_update},
 * {@code calendar_delete}.
 *
 * <p>A call names its account as {@code provider.key} ({@code caldav.web}),
 * or asks {@code all} where a listing can span them. Which kinds exist is
 * {@link CalendarAccounts}' business: CalDAV with that module on the
 * classpath, and whatever else a distribution plugs in.
 *
 * <p>The three that change something are tools of their own, so an agent's
 * binding can make them ask the user first while reading runs freely.
 */
public class CalendarToolProvider implements MultiToolProvider {

    private CalendarTools calendar;

    @Override
    public Set<String> toolNames() {
        return CalendarTools.NAMES;
    }

    @Override
    public String group() {
        return "office";
    }

    @Override
    public String subgroup(String toolName) {
        return "Calendar";
    }

    /** The host's own {@link CalendarAccounts} when it has one, else one on the runtime's connections. */
    @Override
    public void bind(ToolEnvironment env) {
        bind(env.get(CalendarAccounts.class)
                .orElseGet(() -> env.get(Connections.class).map(CalendarAccounts::new).orElse(null)),
                TimeZones.of(env));
    }

    /** The registry directly, in the JVM's zone — for a host without an environment, and for tests. */
    public CalendarToolProvider bind(CalendarAccounts accounts) {
        return bind(accounts, TimeZones.system());
    }

    /**
     * With the zone each user lives in: a time the model writes without an
     * offset is read in the calling user's zone, asked on every call, and the
     * times the tools show are in it too.
     */
    public CalendarToolProvider bind(CalendarAccounts accounts, TimeZones zones) {
        this.calendar = accounts == null ? null
                : new CalendarTools(accounts, zones == null ? TimeZones.system() : zones);
        return this;
    }

    @Override
    public boolean isAvailable() {
        return calendar != null;
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        if (calendar == null || scope == null || scope.userId() == null) return Optional.empty();
        return calendar.create(toolName, scope.userId());
    }
}
