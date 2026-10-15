package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.Tool;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The current date and time in the zone of the user the call runs for —
 * {@code 2026-09-24T17:36:05+02:00[Europe/Zurich]} — not the server's.
 */
public class CurrentDateTimeTool implements Tool {

    private final Clock clock;
    private final Supplier<ZoneId> zone;

    /** The JVM's zone. */
    public CurrentDateTimeTool() {
        this(Clock.systemUTC(), ZoneId::systemDefault);
    }

    /** {@code zone} asked on every call — the calling user's. */
    public CurrentDateTimeTool(Clock clock, Supplier<ZoneId> zone) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public String name() {
        return "get_current_datetime";
    }

    @Override
    public String description() {
        return "Returns the current date and time in the user's time zone, in ISO-8601 with the offset and "
                + "the zone id.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", new String[0]
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return ZonedDateTime.now(clock.withZone(zone.get())).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
    }
}
