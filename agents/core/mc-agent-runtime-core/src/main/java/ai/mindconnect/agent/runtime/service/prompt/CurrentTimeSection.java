package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.tool.TimeZones;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Tells the model what time it is for the user, and in which zone the times
 * it reads and writes are:
 *
 * <pre>
 * ## Date and time
 * Today is Thursday, 24 September 2026 (Europe/Zurich, UTC+02:00).
 * A time without an offset is a time in Europe/Zurich — …
 * </pre>
 *
 * <p>Without it a model knows neither: "tomorrow at four" has no date, and a
 * calendar tool that reads {@code 2026-09-25T16:16} in the user's zone gets
 * whatever the model thought the zone was. The zone is the user's
 * ({@link TimeZones}), never the server's — a server in UTC would otherwise
 * move every appointment by the user's offset.
 *
 * <p>Rendered fresh every round, like every {@link PromptSection} — but to the
 * day, not the minute: the system prompt is the front of every request, and
 * a line that changed every minute would miss the provider's prompt cache on
 * nearly every call. The time itself is one {@code get_current_datetime}
 * call away, in the same zone.
 */
public final class CurrentTimeSection implements PromptSection {

    private static final DateTimeFormatter SPOKEN =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    private final Clock clock;
    private final TimeZones zones;

    public CurrentTimeSection(Clock clock, TimeZones zones) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zones = Objects.requireNonNull(zones, "zones");
    }

    @Override
    public String render(AgentDefinition def, AgentSession session) {
        ZoneId zone = zones.zoneOf(session == null ? null : session.userId());
        String id = zone.getId();
        return "\n\n## Date and time\n" + line(Instant.now(clock), zone)
                + "\nA time without an offset is a time in " + id + " — what the user says (\"tomorrow at "
                + "16:00\") and what you pass to a tool (2026-09-25T16:16 is 16:16 in " + id + "); write "
                + "an offset only for a time that is somewhere else. Times the tools show are in " + id + " too. "
                + "For the time of day, call get_current_datetime.";
    }

    /** {@code Today is Thursday, 24 September 2026 (Europe/Zurich, UTC+02:00).} */
    public static String line(Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        return "Today is " + SPOKEN.format(local) + " (" + zone.getId() + ", " + utc(local.getOffset()) + ").";
    }

    /** {@code UTC+02:00}, {@code UTC-04:00}, {@code UTC+00:00}. */
    static String utc(ZoneOffset offset) {
        return "UTC" + (offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId());
    }
}
