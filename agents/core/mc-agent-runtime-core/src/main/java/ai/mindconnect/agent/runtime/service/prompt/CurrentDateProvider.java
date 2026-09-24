package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.PromptContextProvider;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.TimeZones;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes the current date/time to system-prompt templates, in the time zone
 * of the user the turn runs for ({@link TimeZones}) — not the server's.
 * <p>
 * Variables provided:
 * <ul>
 *   <li>{@code current_date}     — the user's date, ISO-8601, e.g. {@code "2026-04-28"}</li>
 *   <li>{@code current_datetime} — ISO-8601 instant, e.g. {@code "2026-04-28T18:42:00Z"}</li>
 *   <li>{@code current_time}     — the user's local time, e.g. {@code "20:42"}</li>
 *   <li>{@code time_zone}        — the user's zone id, e.g. {@code "Europe/Zurich"}</li>
 * </ul>
 * The clock can be injected for tests. {@link CurrentTimeSection} states the
 * same time in every system prompt, whether the template asks for it or not.
 */
public class CurrentDateProvider implements PromptContextProvider {

    private final Clock clock;
    private final TimeZones zones;

    /** The JVM's clock and zone, for everyone. */
    public CurrentDateProvider() {
        this(Clock.systemDefaultZone());
    }

    /** {@code clock} and its zone, for everyone. */
    public CurrentDateProvider(Clock clock) {
        this(clock, TimeZones.fixed(clock.getZone()));
    }

    /** {@code clock} for the moment, {@code zones} for whose local time it is. */
    public CurrentDateProvider(Clock clock, TimeZones zones) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zones = Objects.requireNonNull(zones, "zones");
    }

    @Override
    public void contribute(Map<String, Object> ctx,
                           AgentDefinition def,
                           AgentSession session,
                           AuthenticationInfo auth) {
        Instant now = Instant.now(clock);
        ZoneId zone = zones.zoneOf(userOf(session, auth));
        LocalDateTime local = LocalDateTime.ofInstant(now, zone);
        ctx.put("current_date", LocalDate.from(local).toString());
        ctx.put("current_datetime", now.toString());
        ctx.put("current_time", local.format(DateTimeFormatter.ofPattern("HH:mm")));
        ctx.put("time_zone", zone.getId());
    }

    /** Whom the turn runs for: the caller, else the session's owner; null on nobody's behalf. */
    static UserId userOf(AgentSession session, AuthenticationInfo auth) {
        if (auth != null) return auth.userId();
        return session == null ? null : session.userId();
    }
}
