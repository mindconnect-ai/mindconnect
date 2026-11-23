package ai.mindconnect.agent.runtime.capabilities.prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.agent.runtime.api.port.out.PromptVariableProvider;
import ai.mindconnect.agent.runtime.api.port.out.TokenCounter;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * What every agent's instructions can count on: the values a template may
 * refer to — the agent, the user, the session, the date, the tools — and a
 * section that says what day it is for the user, whether the template asks
 * or not. Also the dependency-free answer to "how long is this": an
 * estimate, until an exact counter is provided.
 *
 * <p>The section is written to the day, not the minute: the instructions
 * are the front of every request, and a line that changed every minute
 * would miss the provider's prompt cache on nearly every call.
 */
public final class PromptPlugin implements Plugin {

    private static final DateTimeFormatter SPOKEN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm");

    private final Clock clock;
    private final Function<UserId, ZoneId> zoneOf;

    /**
     * @param zoneOf the zone a user lives in, never the server's; asked with
     *               {@code null} for a session nobody owns
     */
    public PromptPlugin(Clock clock, Function<UserId, ZoneId> zoneOf) {
        this.clock = clock;
        this.zoneOf = zoneOf;
    }

    @Override
    public String name() {
        return "prompt";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.provide(TokenCounter.class, EstimatingTokenCounter::new);
        registry.contribute(PromptVariableProvider.class, this::variables);
        registry.after(RuntimeHook.INSTRUCTIONS, (context, instructions) ->
                instructions + dateAndTime(context.session()));
    }

    /**
     * {@code agent_name}, {@code user_id}, {@code session_id},
     * {@code current_date}, {@code current_datetime}, {@code current_time},
     * {@code time_zone} and {@code tools}, a list of {@code name} and
     * {@code description}.
     */
    private Map<String, Object> variables(AgentDefinition agent, Session session) {
        ZonedDateTime local = Instant.now(clock).atZone(zoneOf.apply(session.userId()));
        // Not Map.of: a session nobody owns has no user id, and the template's {% if %} wants the null.
        Map<String, Object> variables = new HashMap<>();
        variables.put("agent_name", agent.name());
        variables.put("user_id", session.userId() == null ? null : session.userId().value());
        variables.put("session_id", session.id().value());
        variables.put("current_date", local.toLocalDate().toString());
        variables.put("current_datetime", local.toInstant().toString());
        variables.put("current_time", TIME_OF_DAY.format(local));
        variables.put("time_zone", local.getZone().getId());
        variables.put("tools", agent.tools().stream()
                .map(tool -> Map.of("name", tool.name(),
                        "description", tool.metadata().get("description") instanceof String text ? text : ""))
                .toList());
        return variables;
    }

    private String dateAndTime(Session session) {
        ZonedDateTime local = Instant.now(clock).atZone(zoneOf.apply(session.userId()));
        String zone = local.getZone().getId();
        String offset = local.getOffset().getTotalSeconds() == 0 ? "+00:00" : local.getOffset().getId();
        return "\n\n## Date and time\nToday is " + SPOKEN.format(local) + " (" + zone + ", UTC" + offset + ")."
                + "\nA time without an offset is a time in " + zone + " — what the user says (\"tomorrow at "
                + "16:00\") and what you pass to a tool (2026-09-25T16:16 is 16:16 in " + zone + "); write "
                + "an offset only for a time that is somewhere else. Times the tools show are in " + zone + " too. "
                + "For the time of day, call get_current_datetime.";
    }
}
